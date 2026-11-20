package com.bookworm.wishlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.bookworm.cart.ItemAvailability;
import com.bookworm.catalogue.BookInfo;
import com.bookworm.catalogue.BookLookupService;
import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import com.bookworm.common.web.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class WishlistServiceTest {

    private static final Long USER = 8L;

    private final WishlistItemRepository items = mock(WishlistItemRepository.class);
    private final BookLookupService books = mock(BookLookupService.class);
    private final WishlistService service = new WishlistService(items, books);

    private final Map<Long, BookInfo> catalogue = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(items.save(any(WishlistItem.class))).thenAnswer(i -> i.getArgument(0));
        when(books.findById(any())).thenAnswer(i -> Optional.ofNullable(catalogue.get(i.<Long>getArgument(0))));
        when(books.findByIds(any())).thenAnswer(i -> {
            Map<Long, BookInfo> found = new HashMap<>();
            for (Long id : i.<Iterable<Long>>getArgument(0)) {
                if (catalogue.containsKey(id)) {
                    found.put(id, catalogue.get(id));
                }
            }
            return found;
        });
    }

    private static BookInfo activeBook(Long id, String title, int stock, BigDecimal price) {
        return new BookInfo(id, "978000000000" + id, title,
                List.of(new BookInfo.AuthorRef(1L, "Author One")),
                new BookInfo.CategoryRef(2L, "Fiction", "fiction"),
                price, "https://cover/" + id, true, stock);
    }

    private static BookInfo inactiveBook(Long id, String title) {
        return new BookInfo(id, "978000000000" + id, title,
                List.of(new BookInfo.AuthorRef(1L, "Author One")),
                new BookInfo.CategoryRef(2L, "Fiction", "fiction"),
                BigDecimal.TEN, "https://cover/" + id, false, 5);
    }

    // ---- get / pagination / rendering tests -----------------------------------------------------------------

    @Test
    void getReturnsEmptyPageResponseWhenNoItems() {
        when(items.findByUserId(anyLong(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageResponse<WishlistItemResponse> response = service.get(USER, 0, 20, "addedAt,desc");

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    @Test
    void getRendersActiveAndInactiveBooksCorrectly() {
        catalogue.put(1L, activeBook(1L, "Active Available", 5, new BigDecimal("199.99")));
        catalogue.put(2L, activeBook(2L, "Active Out Of Stock", 0, new BigDecimal("299.99")));
        catalogue.put(3L, inactiveBook(3L, "Inactive Book"));

        WishlistItem item1 = WishlistItem.create(USER, 1L);
        WishlistItem item2 = WishlistItem.create(USER, 2L);
        WishlistItem item3 = WishlistItem.create(USER, 3L);

        when(items.findByUserId(anyLong(), any())).thenReturn(
                new PageImpl<>(List.of(item1, item2, item3), PageRequest.of(0, 20), 3));

        PageResponse<WishlistItemResponse> response = service.get(USER, 0, 20, "addedAt,desc");

        assertThat(response.content()).hasSize(3);

        WishlistItemResponse r1 = response.content().get(0);
        assertThat(r1.bookId()).isEqualTo(1L);
        assertThat(r1.title()).isEqualTo("Active Available");
        assertThat(r1.availability()).isEqualTo(ItemAvailability.AVAILABLE);
        assertThat(r1.price()).isEqualByComparingTo("199.99");
        assertThat(r1.authors()).hasSize(1);
        assertThat(r1.category().slug()).isEqualTo("fiction");

        WishlistItemResponse r2 = response.content().get(1);
        assertThat(r2.bookId()).isEqualTo(2L);
        assertThat(r2.title()).isEqualTo("Active Out Of Stock");
        assertThat(r2.availability()).isEqualTo(ItemAvailability.OUT_OF_STOCK);

        WishlistItemResponse r3 = response.content().get(2);
        assertThat(r3.bookId()).isEqualTo(3L);
        assertThat(r3.title()).isEqualTo("Inactive Book");
        assertThat(r3.availability()).isEqualTo(ItemAvailability.UNAVAILABLE);
        assertThat(r3.price()).isNull();
        assertThat(r3.isbn()).isNull();
        assertThat(r3.authors()).isNull();
        assertThat(r3.category()).isNull();
        assertThat(r3.coverUrl()).isNull();
        assertThat(r3.addedAt()).isNull();
    }

    @Test
    void getRejectsNegativePage() {
        assertThatThrownBy(() -> service.get(USER, -1, 20, "addedAt,desc"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void getRejectsInvalidSize() {
        assertThatThrownBy(() -> service.get(USER, 0, 0, "addedAt,desc"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.get(USER, 0, 101, "addedAt,desc"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void getRejectsUnsupportedSort() {
        assertThatThrownBy(() -> service.get(USER, 0, 20, "title"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.get(USER, 0, 20, "addedAt,invalid"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.get(USER, 0, 20, "addedAt,asc,desc"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void getAcceptsValidSorts() {
        when(items.findByUserId(anyLong(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.get(USER, 0, 20, null);
        service.get(USER, 0, 20, "addedAt");
        service.get(USER, 0, 20, "addedAt,asc");
        service.get(USER, 0, 20, "addedAt,desc");

        verify(items, times(3)).findByUserId(USER, PageRequest.of(0, 20,
                Sort.by(Sort.Direction.DESC, "addedAt").and(Sort.by(Sort.Direction.DESC, "id"))));
        verify(items, times(1)).findByUserId(USER, PageRequest.of(0, 20,
                Sort.by(Sort.Direction.ASC, "addedAt").and(Sort.by(Sort.Direction.ASC, "id"))));
    }

    // ---- add tests ------------------------------------------------------------------------------------------

    @Test
    void addAcquiresAdvisoryLockBeforeChecks() {
        catalogue.put(1L, activeBook(1L, "Book", 5, BigDecimal.TEN));
        when(items.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.empty());
        when(items.countByUserId(USER)).thenReturn(0L);

        service.add(USER, 1L);

        InOrder inOrder = inOrder(items, books);
        inOrder.verify(items).acquireAdvisoryLock(WishlistService.lockKey(USER));
        inOrder.verify(books).findById(1L);
    }

    @Test
    void addRejectsUnknownBookWith404() {
        assertThatThrownBy(() -> service.add(USER, 999L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(items, never()).save(any());
    }

    @Test
    void addRejectsInactiveBookWith404EvenIfAlreadyInWishlist() {
        catalogue.put(1L, inactiveBook(1L, "Inactive"));
        when(items.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.of(WishlistItem.create(USER, 1L)));

        assertThatThrownBy(() -> service.add(USER, 1L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(items, never()).save(any());
    }

    @Test
    void addReturnsExistingItemWithCreatedFalseWhenAlreadyPresent() {
        catalogue.put(1L, activeBook(1L, "Active Book", 5, BigDecimal.TEN));
        WishlistItem existing = WishlistItem.create(USER, 1L);
        when(items.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.of(existing));

        WishlistService.AddResult result = service.add(USER, 1L);

        assertThat(result.created()).isFalse();
        assertThat(result.item().bookId()).isEqualTo(1L);
        verify(items, never()).save(any());
    }

    @Test
    void addSavesNewItemWhenNotPresent() {
        catalogue.put(1L, activeBook(1L, "Active Book", 5, BigDecimal.TEN));
        when(items.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.empty());
        when(items.countByUserId(USER)).thenReturn(10L);

        WishlistService.AddResult result = service.add(USER, 1L);

        assertThat(result.created()).isTrue();
        assertThat(result.item().bookId()).isEqualTo(1L);
        verify(items).save(any(WishlistItem.class));
    }

    @Test
    void addRejectsWhenWishlistFullAt200Items() {
        catalogue.put(1L, activeBook(1L, "Active Book", 5, BigDecimal.TEN));
        when(items.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.empty());
        when(items.countByUserId(USER)).thenReturn(200L);

        assertThatThrownBy(() -> service.add(USER, 1L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.WISHLIST_FULL));

        verify(items, never()).save(any());
    }

    // ---- remove tests ---------------------------------------------------------------------------------------

    @Test
    void removeCallsDeleteByUserIdAndBookIdIdempotently() {
        service.remove(USER, 1L);

        verify(items).deleteByUserIdAndBookId(USER, 1L);
    }

    // ---- lock key test --------------------------------------------------------------------------------------

    @Test
    void lockKeyIsDeterministicAndUserScoped() {
        long key1 = WishlistService.lockKey(10L);
        long key2 = WishlistService.lockKey(10L);
        long key3 = WishlistService.lockKey(11L);

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).isNotEqualTo(key3);
    }
}
