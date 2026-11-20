package com.bookworm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BookLookupServiceTest {

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final BookLookupService service = new BookLookupService(bookRepository);

    @Test
    void findByIdsReturnsActiveAndInactiveBooksKeyedById() {
        Book active = book(1L, "Active", true, 5);
        Book inactive = book(2L, "Inactive", false, 0);
        when(bookRepository.findAllById(any())).thenReturn(List.of(active, inactive));

        Map<Long, BookInfo> result = service.findByIds(List.of(1L, 2L));

        assertThat(result).containsOnlyKeys(1L, 2L);
        assertThat(result.get(1L).active()).isTrue();
        assertThat(result.get(2L).active()).isFalse();
        assertThat(result.get(2L).title()).isEqualTo("Inactive");
    }

    @Test
    void unknownIdsAreSimplyAbsent() {
        Book only = book(1L, "Only", true, 3);
        when(bookRepository.findAllById(any())).thenReturn(List.of(only));

        Map<Long, BookInfo> result = service.findByIds(List.of(1L, 999L));

        assertThat(result).containsOnlyKeys(1L);
    }

    @Test
    void emptyOrNullIdsReturnEmptyWithoutTouchingTheRepository() {
        assertThat(service.findByIds(List.of())).isEmpty();
        assertThat(service.findByIds(null)).isEmpty();

        verifyNoInteractions(bookRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateIdsAreCollapsedBeforeTheQuery() {
        when(bookRepository.findAllById(any())).thenReturn(List.of());

        service.findByIds(List.of(4L, 4L, 5L, 4L));

        ArgumentCaptor<Iterable<Long>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(bookRepository).findAllById(captor.capture());
        List<Long> passed = new ArrayList<>();
        captor.getValue().forEach(passed::add);
        assertThat(passed).containsExactly(4L, 5L);
    }

    @Test
    void theResultMapIsNotModifiable() {
        Book one = book(1L, "A", true, 1);
        when(bookRepository.findAllById(any())).thenReturn(List.of(one));

        Map<Long, BookInfo> result = service.findByIds(Set.of(1L));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> result.put(9L, result.get(1L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void findByIdReturnsAnInactiveBook() {
        Book gone = book(7L, "Gone", false, 0);
        when(bookRepository.findById(7L)).thenReturn(Optional.of(gone));

        Optional<BookInfo> result = service.findById(7L);

        assertThat(result).isPresent();
        assertThat(result.get().active()).isFalse();
    }

    @Test
    void findByIdIsEmptyForAnUnknownBookAndForNull() {
        when(bookRepository.findById(8L)).thenReturn(Optional.empty());

        assertThat(service.findById(8L)).isEmpty();
        assertThat(service.findById(null)).isEmpty();
        verify(bookRepository, never()).findById(null);
    }

    @Test
    void theInfoCarriesEverythingTheCartNeedsAndNoEntity() {
        Book book = book(3L, "Dune", true, 12);
        when(book.getIsbn()).thenReturn("9780000000036");
        when(book.getCoverUrl()).thenReturn("https://img.example.test/dune.jpg");
        when(book.getPrice()).thenReturn(new BigDecimal("199.99"));
        Author ada = author(10L, "Ada");
        Author bo = author(11L, "Bo");
        when(book.getAuthors()).thenReturn(List.of(ada, bo));
        when(bookRepository.findAllById(any())).thenReturn(List.of(book));

        BookInfo info = service.findByIds(List.of(3L)).get(3L);

        assertThat(info.id()).isEqualTo(3L);
        assertThat(info.isbn()).isEqualTo("9780000000036");
        assertThat(info.title()).isEqualTo("Dune");
        assertThat(info.price()).isEqualByComparingTo("199.99");
        assertThat(info.coverUrl()).isEqualTo("https://img.example.test/dune.jpg");
        assertThat(info.stockQuantity()).isEqualTo(12);
        assertThat(info.authors()).extracting(BookInfo.AuthorRef::id, BookInfo.AuthorRef::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10L, "Ada"),
                        org.assertj.core.groups.Tuple.tuple(11L, "Bo"));
        assertThat(info.category().id()).isEqualTo(7L);
        assertThat(info.category().slug()).isEqualTo("fantasy");
    }

    @Test
    void theInfoRecordExposesNoEntityTypes() {
        for (RecordComponent component : BookInfo.class.getRecordComponents()) {
            assertThat(component.getType().getName()).as(component.getName()).doesNotContain("Book;");
            assertThat(component.getType()).isNotIn(Book.class, Author.class, Category.class);
        }
        assertThat(BookInfo.class.getRecordComponents()).extracting(RecordComponent::getName)
                .doesNotContain("version", "description");
    }

    @Test
    void theServiceOnlyReadsAndDependsOnlyOnThePublicRepository() {
        Book forList = book(1L, "A", true, 1);
        Book forSingle = book(1L, "A", true, 1);
        when(bookRepository.findAllById(any())).thenReturn(List.of(forList));
        when(bookRepository.findById(1L)).thenReturn(Optional.of(forSingle));

        service.findByIds(List.of(1L));
        service.findById(1L);

        verify(bookRepository).findAllById(any());
        verify(bookRepository).findById(1L);
        verifyNoMoreInteractions(bookRepository);

        // No Admin* collaborator (the admin entities and services are off limits to cart and wishlist code).
        assertThat(BookLookupService.class.getDeclaredFields()).extracting(f -> f.getType().getSimpleName())
                .containsExactly("BookRepository");
    }

    private static Book book(long id, String title, boolean active, int stock) {
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        when(book.getTitle()).thenReturn(title);
        when(book.getPrice()).thenReturn(new BigDecimal("100.00"));
        when(book.isActive()).thenReturn(active);
        when(book.getStockQuantity()).thenReturn(stock);
        when(book.getAuthors()).thenReturn(List.of());
        Category category = mock(Category.class);
        when(category.getId()).thenReturn(7L);
        when(category.getName()).thenReturn("Fantasy");
        when(category.getSlug()).thenReturn("fantasy");
        when(book.getCategory()).thenReturn(category);
        return book;
    }

    private static Author author(long id, String name) {
        Author author = mock(Author.class);
        when(author.getId()).thenReturn(id);
        when(author.getName()).thenReturn(name);
        return author;
    }
}
