package com.bookworm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import com.bookworm.common.web.PageResponse;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class CatalogueServiceTest {

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final CatalogueService service = new CatalogueService(bookRepository, categoryRepository);

    private final Category category = category(7L, "Fantasy", "fantasy");

    @Test
    void searchPassesEscapedLowerCasePatternAndSlugToTheRepository() {
        when(bookRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.search("  100%_X  ", "fantasy", 0, 20, "title", Sort.Direction.ASC);

        verify(bookRepository).search(eq("fantasy"), eq("%100\\%\\_x%"), any(Pageable.class));
    }

    @Test
    void blankQueryAndMissingCategoryBecomeEmptyFilters() {
        when(bookRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.search("   ", null, 0, 20, "title", Sort.Direction.ASC);

        verify(bookRepository).search(eq(""), eq(""), any(Pageable.class));
    }

    @Test
    void searchSortsByRequestedFieldWithIdAsStableTieBreaker() {
        when(bookRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.search(null, null, 2, 5, "price", Sort.Direction.DESC);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(bookRepository).search(eq(""), eq(""), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().toList()).extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("price", Sort.Direction.DESC),
                        org.assertj.core.groups.Tuple.tuple("id", Sort.Direction.ASC));
    }

    @Test
    void unsupportedSortFieldIsRejectedBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.search(null, null, 0, 20, "stockQuantity", Sort.Direction.ASC))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(bookRepository, never()).search(any(), any(), any());
    }

    @Test
    void searchResultsExposeInStockButNeverTheStockCount() {
        Book inStock = book(1L, "A", 3);
        Book soldOut = book(2L, "B", 0);
        when(bookRepository.search(any(), any(), any())).thenReturn(new PageImpl<>(List.of(inStock, soldOut)));

        PageResponse<BookSummaryResponse> result = service.search(null, null, 0, 20, "title", Sort.Direction.ASC);

        assertThat(result.content()).extracting(BookSummaryResponse::inStock).containsExactly(true, false);
        assertThat(BookSummaryResponse.class.getRecordComponents())
                .extracting(c -> c.getName()).doesNotContain("stockQuantity", "stock");
        assertThat(BookDetailResponse.class.getRecordComponents())
                .extracting(c -> c.getName()).doesNotContain("stockQuantity", "stock");
    }

    @Test
    void getBookThrowsNotFoundForUnknownOrInactiveBook() {
        when(bookRepository.findActiveById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBook(99L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void relatedBooksStopAtTheLimitWhenSharedAuthorBooksAreEnough() {
        Book self = book(1L, "Self", 1);
        List<Book> sharedAuthor = List.of(book(2L, "A", 1), book(3L, "B", 1));
        when(bookRepository.findActiveById(1L)).thenReturn(Optional.of(self));
        when(bookRepository.findSharingAuthor(eq(1L), any(Pageable.class))).thenReturn(sharedAuthor);

        List<BookSummaryResponse> related = service.getRelated(1L, 2);

        assertThat(related).extracting(BookSummaryResponse::id).containsExactly(2L, 3L);
        verify(bookRepository, never()).findInCategoryExcluding(any(), anyCollection(), any());
    }

    @Test
    void relatedBooksFillTheRemainingSlotsFromTheCategoryExcludingSelfAndAlreadyChosen() {
        Book self = book(1L, "Self", 1);
        List<Book> sharedAuthor = List.of(book(2L, "A", 1));
        List<Book> sameCategory = List.of(book(4L, "C", 1), book(5L, "D", 1));
        when(bookRepository.findActiveById(1L)).thenReturn(Optional.of(self));
        when(bookRepository.findSharingAuthor(eq(1L), any(Pageable.class))).thenReturn(sharedAuthor);
        when(bookRepository.findInCategoryExcluding(eq(7L), anyCollection(), any(Pageable.class)))
                .thenReturn(sameCategory);

        List<BookSummaryResponse> related = service.getRelated(1L, 3);

        assertThat(related).extracting(BookSummaryResponse::id).containsExactly(2L, 4L, 5L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> excluded = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> limit = ArgumentCaptor.forClass(Pageable.class);
        verify(bookRepository).findInCategoryExcluding(eq(7L), excluded.capture(), limit.capture());
        assertThat(excluded.getValue()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(limit.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    void relatedBooksOfAnUnknownBookIsNotFound() {
        when(bookRepository.findActiveById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRelated(1L, 8))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private Book book(long id, String title, int stock) {
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        when(book.getTitle()).thenReturn(title);
        when(book.getPrice()).thenReturn(new BigDecimal("100.00"));
        when(book.getStockQuantity()).thenReturn(stock);
        when(book.getCategory()).thenReturn(category);
        when(book.getAuthors()).thenReturn(List.of());
        return book;
    }

    private static Category category(long id, String name, String slug) {
        Category category = mock(Category.class);
        when(category.getId()).thenReturn(id);
        when(category.getName()).thenReturn(name);
        when(category.getSlug()).thenReturn(slug);
        return category;
    }
}
