package com.bookworm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class AdminCategoryAndAuthorServiceTest {

    private AdminCategoryRepository categories;
    private AdminBookRepository books;
    private AdminAuthorRepository authors;
    private CategoryWriter writer;
    private AdminCategoryService categoryService;
    private AdminAuthorService authorService;

    @BeforeEach
    void setUp() {
        categories = mock(AdminCategoryRepository.class);
        books = mock(AdminBookRepository.class);
        authors = mock(AdminAuthorRepository.class);
        writer = mock(CategoryWriter.class);
        categoryService = new AdminCategoryService(categories, books, writer);
        authorService = new AdminAuthorService(authors, books);
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("x", new RuntimeException(
                "ERROR: duplicate key value violates unique constraint \"" + constraint + "\""));
    }

    private static AdminCategory saved(long id, String name, String slug) {
        AdminCategory category = AdminCategory.create(name, slug);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    // ---- category create ----------------------------------------------------------------------

    @Test
    void createCategoryGeneratesTheSlugFromTheNormalisedName() {
        when(writer.insert("Science Fiction", "science-fiction"))
                .thenReturn(saved(1L, "Science Fiction", "science-fiction"));

        AdminCategoryResponse response = categoryService.create(7L, new CategoryRequest("  Science   Fiction "));

        assertThat(response.slug()).isEqualTo("science-fiction");
        assertThat(response.bookCount()).isZero();
    }

    @Test
    void aSlugCollisionRetriesWithTheNextSuffix() {
        when(writer.insert("Sci Fi", "sci-fi")).thenThrow(violation("uk_categories_slug"));
        when(writer.insert("Sci Fi", "sci-fi-2")).thenThrow(violation("uk_categories_slug"));
        when(writer.insert("Sci Fi", "sci-fi-3")).thenReturn(saved(4L, "Sci Fi", "sci-fi-3"));

        AdminCategoryResponse response = categoryService.create(7L, new CategoryRequest("Sci Fi"));

        assertThat(response.slug()).isEqualTo("sci-fi-3");
        ArgumentCaptor<String> slugs = ArgumentCaptor.forClass(String.class);
        verify(writer, times(3)).insert(anyString(), slugs.capture());
        assertThat(slugs.getAllValues()).containsExactly("sci-fi", "sci-fi-2", "sci-fi-3");
    }

    @Test
    void aNameRaceIsReportedAsDuplicateAndIsNotRetried() {
        when(writer.insert(anyString(), anyString())).thenThrow(violation("ux_categories_name_ci"));

        assertThatThrownBy(() -> categoryService.create(7L, new CategoryRequest("Poetry")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
        verify(writer, times(1)).insert(anyString(), anyString());
    }

    @Test
    void anUnrelatedIntegrityFailureIsRethrownNotRetried() {
        DataIntegrityViolationException other = violation("some_other_constraint");
        when(writer.insert(anyString(), anyString())).thenThrow(other);

        assertThatThrownBy(() -> categoryService.create(7L, new CategoryRequest("Poetry"))).isSameAs(other);
        verify(writer, times(1)).insert(anyString(), anyString());
    }

    @Test
    void anExistingNameIgnoringCaseIsRejectedBeforeInserting() {
        when(categories.existsByNameIgnoreCase("Poetry")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(7L, new CategoryRequest("Poetry")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
        verify(writer, never()).insert(anyString(), anyString());
    }

    @Test
    void aNameWithNoLatinLettersOrDigitsCannotProduceASlug() {
        assertThatThrownBy(() -> categoryService.create(7L, new CategoryRequest("कविता")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldProblems()).extracting(ApiException.FieldProblem::field).containsExactly("name");
                });
        verify(writer, never()).insert(anyString(), anyString());
    }

    @Test
    void unsafeCategoryNamesAreRejectedWithAFieldError() {
        for (String name : List.of("Bad\u0000Name", "Zero\u200bWidth", "<script>", "Ｆｕｌｌ", "x".repeat(101))) {
            assertThatThrownBy(() -> categoryService.create(7L, new CategoryRequest(name)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getErrorCode()).as(name).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        verify(writer, never()).insert(anyString(), anyString());
    }

    // ---- category rename ----------------------------------------------------------------------

    @Test
    void renameKeepsTheSlug() {
        AdminCategory category = saved(3L, "Old", "old");
        when(categories.findById(3L)).thenReturn(Optional.of(category));
        when(books.countByCategory(any())).thenReturn(List.of(new IdCount(3L, 4)));

        AdminCategoryResponse response = categoryService.rename(7L, 3L, new CategoryRequest("Brand New"));

        assertThat(response.name()).isEqualTo("Brand New");
        assertThat(response.slug()).isEqualTo("old");
        assertThat(response.bookCount()).isEqualTo(4);
    }

    @Test
    void renameToAnotherCategorysNameIsADuplicate() {
        when(categories.findById(3L)).thenReturn(Optional.of(saved(3L, "Old", "old")));
        when(categories.existsByNameIgnoreCaseAndIdNot("Taken", 3L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.rename(7L, 3L, new CategoryRequest("Taken")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    }

    @Test
    void renameRaceOnTheNameConstraintIsADuplicate() {
        when(categories.findById(3L)).thenReturn(Optional.of(saved(3L, "Old", "old")));
        org.mockito.Mockito.doThrow(violation("ux_categories_name_ci")).when(categories).flush();

        assertThatThrownBy(() -> categoryService.rename(7L, 3L, new CategoryRequest("Racy")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    }

    @Test
    void renamingAMissingCategoryIsNotFound() {
        when(categories.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.rename(7L, 3L, new CategoryRequest("X")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void categoryListUsesOneGroupedCountQuery() {
        when(categories.findAllByOrderByNameAscIdAsc())
                .thenReturn(List.of(saved(1L, "A", "a"), saved(2L, "B", "b")));
        when(books.countByCategory(any())).thenReturn(List.of(new IdCount(2L, 5)));

        List<AdminCategoryResponse> list = categoryService.list();

        assertThat(list).extracting(AdminCategoryResponse::bookCount).containsExactly(0L, 5L);
        verify(books, times(1)).countByCategory(any());
    }

    // ---- authors ------------------------------------------------------------------------------

    @Test
    void createAuthorRejectsADuplicateIgnoringCase() {
        when(authors.existsByNameIgnoreCase("Ann Lee")).thenReturn(true);

        assertThatThrownBy(() -> authorService.create(7L, new AuthorRequest("Ann Lee")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
        verify(authors, never()).saveAndFlush(any());
    }

    @Test
    void createAuthorMapsTheNameRaceToDuplicateResource() {
        when(authors.saveAndFlush(any(AdminAuthor.class))).thenThrow(violation("ux_authors_name_ci"));

        assertThatThrownBy(() -> authorService.create(7L, new AuthorRequest("Ann Lee")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    }

    @Test
    void createAuthorDoesNotHideOtherIntegrityFailures() {
        DataIntegrityViolationException other = violation("something_else");
        when(authors.saveAndFlush(any(AdminAuthor.class))).thenThrow(other);

        assertThatThrownBy(() -> authorService.create(7L, new AuthorRequest("Ann Lee"))).isSameAs(other);
    }

    @Test
    void createAuthorNormalisesTheName() {
        when(authors.saveAndFlush(any(AdminAuthor.class))).thenAnswer(i -> i.getArgument(0));

        AdminAuthorResponse response = authorService.create(7L, new AuthorRequest("  Ann    Lee "));

        assertThat(response.name()).isEqualTo("Ann Lee");
    }

    @Test
    void renameAuthorToAnotherAuthorsNameIsADuplicate() {
        AdminAuthor author = AdminAuthor.create("Old");
        ReflectionTestUtils.setField(author, "id", 2L);
        when(authors.findById(2L)).thenReturn(Optional.of(author));
        when(authors.existsByNameIgnoreCaseAndIdNot("Taken", 2L)).thenReturn(true);

        assertThatThrownBy(() -> authorService.rename(7L, 2L, new AuthorRequest("Taken")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    }

    @Test
    void authorSearchRejectsASortFieldOutsideTheAllowList() {
        assertThatThrownBy(() -> authorService.search(null, 0, 20, "bookCount",
                org.springframework.data.domain.Sort.Direction.ASC))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
