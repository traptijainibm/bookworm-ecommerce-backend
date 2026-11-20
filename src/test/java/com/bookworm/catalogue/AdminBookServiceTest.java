package com.bookworm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

class AdminBookServiceTest {

    private AdminBookRepository books;
    private AdminCategoryRepository categories;
    private AdminAuthorRepository authors;
    private AdminBookService service;

    private AdminCategory category;
    private AdminAuthor authorOne;
    private AdminAuthor authorTwo;

    @BeforeEach
    void setUp() {
        books = mock(AdminBookRepository.class);
        categories = mock(AdminCategoryRepository.class);
        authors = mock(AdminAuthorRepository.class);
        service = new AdminBookService(books, categories, authors);

        category = AdminCategory.create("It Cat", "it-cat");
        ReflectionTestUtils.setField(category, "id", 1L);
        authorOne = AdminAuthor.create("It One");
        ReflectionTestUtils.setField(authorOne, "id", 1L);
        authorTwo = AdminAuthor.create("It Two");
        ReflectionTestUtils.setField(authorTwo, "id", 2L);
        when(categories.findById(1L)).thenReturn(Optional.of(category));
        when(authors.findAllById(any())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            List<AdminAuthor> found = new java.util.ArrayList<>();
            for (Long id : ids) {
                if (id == 1L) {
                    found.add(authorOne);
                } else if (id == 2L) {
                    found.add(authorTwo);
                }
            }
            return found;
        });
    }

    private static BookCreateRequest create(String isbn, List<Long> authorIds, Long categoryId) {
        return new BookCreateRequest(isbn, "It Title", "desc", new BigDecimal("10.00"), "Pub", null,
                "https://img.example.test/c.jpg", categoryId, authorIds);
    }

    private static BookUpdateRequest update(long version, String isbn) {
        return new BookUpdateRequest(version, isbn, "It New Title", null, new BigDecimal("11.00"), null, null, null,
                1L, List.of(1L));
    }

    private AdminBook existing(long id, long version, boolean active) {
        BookValues values = new BookValues("9780306406157", "Old", null, new BigDecimal("5.00"), null, null, null);
        AdminBook book = AdminBook.create(values, category, Set.of(authorOne));
        ReflectionTestUtils.setField(book, "id", id);
        ReflectionTestUtils.setField(book, "version", version);
        book.setActive(active);
        return book;
    }

    // ---- create -------------------------------------------------------------------------------

    @Test
    void createStoresTheCanonicalIsbnAndStartsInactiveWithoutTouchingStock() {
        when(books.existsByIsbn("9780306406157")).thenReturn(false);
        when(books.saveAndFlush(any(AdminBook.class))).thenAnswer(i -> i.getArgument(0));

        AdminBookResponse response = service.create(7L, create("0-306-40615-2", List.of(1L, 2L), 1L));

        assertThat(response.isbn()).isEqualTo("9780306406157");
        assertThat(response.active()).isFalse();
        assertThat(response.stockQuantity()).isZero();
        assertThat(response.authors()).extracting(AdminBookResponse.Author::name).containsExactly("It One", "It Two");
    }

    @Test
    void createReportsAllProblemsTogetherAsFieldErrors() {
        when(categories.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(7L, create("not-an-isbn", List.of(1L, 1L), 99L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldProblems()).extracting(ApiException.FieldProblem::field)
                            .containsExactlyInAnyOrder("isbn", "categoryId", "authorIds");
                });
        verify(books, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsUnknownAuthorIds() {
        assertThatThrownBy(() -> service.create(7L, create("9780306406157", List.of(1L, 99L), 1L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldProblems()).extracting(ApiException.FieldProblem::field)
                            .containsExactly("authorIds");
                });
    }

    @Test
    void createRejectsDuplicateAuthorIdsBeforeQueryingTheDatabase() {
        assertThatThrownBy(() -> service.create(7L, create("9780306406157", List.of(2L, 2L), 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getFieldProblems()).extracting(ApiException.FieldProblem::message)
                                .contains("must not contain duplicates"));
        verify(authors, never()).findAllById(any());
    }

    @Test
    void createRejectsAnIsbnThatAlreadyExistsEvenWhenWrittenAsIsbn10() {
        when(books.existsByIsbn("9780306406157")).thenReturn(true);

        assertThatThrownBy(() -> service.create(7L, create("0306406152", List.of(1L), 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
        verify(books, never()).saveAndFlush(any());
    }

    @Test
    void aRaceOnTheIsbnUniqueConstraintIsReportedAsDuplicateResource() {
        when(books.saveAndFlush(any(AdminBook.class))).thenThrow(new DataIntegrityViolationException("x",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"uk_books_isbn\"")));

        assertThatThrownBy(() -> service.create(7L, create("9780306406157", List.of(1L), 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    }

    @Test
    void anotherIntegrityFailureIsNotMistakenForADuplicate() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("x",
                new RuntimeException("ERROR: new row violates check constraint \"ck_books_price_positive\""));
        when(books.saveAndFlush(any(AdminBook.class))).thenThrow(other);

        assertThatThrownBy(() -> service.create(7L, create("9780306406157", List.of(1L), 1L))).isSameAs(other);
    }

    // ---- update -------------------------------------------------------------------------------

    @Test
    void updateWithAStaleVersionIsAConcurrentUpdateConflictAndWritesNothing() {
        when(books.findDetailById(5L)).thenReturn(Optional.of(existing(5L, 3L, true)));

        assertThatThrownBy(() -> service.update(7L, 5L, update(2L, "9780306406157")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_UPDATE));
        verify(books, never()).saveAndFlush(any());
    }

    @Test
    void updateOfAMissingBookIsNotFound() {
        when(books.findDetailById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(7L, 5L, update(0L, "9780306406157")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void updateChangesDescriptiveFieldsButNeverTheActiveFlag() {
        AdminBook book = existing(5L, 3L, true);
        when(books.findDetailById(5L)).thenReturn(Optional.of(book));
        when(books.existsByIsbnAndIdNot("9780306406157", 5L)).thenReturn(false);
        when(books.saveAndFlush(any(AdminBook.class))).thenAnswer(i -> i.getArgument(0));

        AdminBookResponse response = service.update(7L, 5L, update(3L, "9780306406157"));

        assertThat(response.title()).isEqualTo("It New Title");
        assertThat(response.price()).isEqualByComparingTo("11.00");
        assertThat(response.active()).isTrue();
    }

    @Test
    void updateRejectsAnIsbnOwnedByAnotherBook() {
        when(books.findDetailById(5L)).thenReturn(Optional.of(existing(5L, 3L, true)));
        when(books.existsByIsbnAndIdNot("9780306406157", 5L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(7L, 5L, update(3L, "9780306406157")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    }

    // ---- active flag --------------------------------------------------------------------------

    @Test
    void settingTheCurrentActiveValueIsANoOpAndDoesNotFlush() {
        when(books.findDetailById(5L)).thenReturn(Optional.of(existing(5L, 3L, true)));

        AdminBookResponse response = service.setActive(7L, 5L, new BookActiveRequest(true));

        assertThat(response.active()).isTrue();
        verify(books, never()).flush();
    }

    @Test
    void changingTheActiveValueFlushesOnce() {
        when(books.findDetailById(5L)).thenReturn(Optional.of(existing(5L, 3L, false)));

        AdminBookResponse response = service.setActive(7L, 5L, new BookActiveRequest(true));

        assertThat(response.active()).isTrue();
        verify(books).flush();
    }

    @Test
    void setActiveOnAMissingBookIsNotFound() {
        when(books.findDetailById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setActive(7L, 5L, new BookActiveRequest(true)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---- search -------------------------------------------------------------------------------

    @Test
    void searchRejectsASortFieldOutsideTheAllowList() {
        assertThatThrownBy(() -> service.search(null, null, null, 0, 20, "stockQuantity", Sort.Direction.ASC))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(books, never()).search(anyString(), anyString(), anyLong(), anyInt(), any());
    }

    // ---- stock protection ---------------------------------------------------------------------

    @Test
    void stockQuantityIsMappedReadOnlyAndHasNoSetterOrWriter() throws Exception {
        Field stock = AdminBook.class.getDeclaredField("stockQuantity");
        Column column = stock.getAnnotation(Column.class);
        assertThat(column).isNotNull();
        assertThat(column.insertable()).isFalse();
        assertThat(column.updatable()).isFalse();

        assertThat(Arrays.stream(AdminBook.class.getDeclaredMethods()).map(m -> m.getName().toLowerCase()))
                .noneMatch(n -> n.contains("stock") && !n.equals("getstockquantity"));
    }

    @Test
    void theBookRequestsHaveNoStockActiveIdOrRoleComponent() {
        for (Class<?> type : List.of(BookCreateRequest.class, BookUpdateRequest.class)) {
            assertThat(Arrays.stream(type.getRecordComponents()).map(c -> c.getName()))
                    .doesNotContain("stockQuantity", "stock", "active", "id", "role");
        }
    }
}
