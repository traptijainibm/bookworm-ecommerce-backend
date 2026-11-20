package com.bookworm.wishlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookworm.cart.Cart;
import com.bookworm.cart.CartItem;
import com.bookworm.cart.CartItemRepository;
import com.bookworm.cart.CartRepository;
import com.bookworm.cart.CartResponse;
import com.bookworm.catalogue.BookInfo;
import com.bookworm.catalogue.BookLookupService;
import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WishlistMoveServiceTest {

    private static final Long USER = 8L;
    private static final Long CART_ID = 5L;

    private final CartRepository carts = mock(CartRepository.class);
    private final CartItemRepository cartItems = mock(CartItemRepository.class);
    private final WishlistItemRepository wishlistItems = mock(WishlistItemRepository.class);
    private final BookLookupService books = mock(BookLookupService.class);
    private final WishlistMoveService service = new WishlistMoveService(carts, cartItems, wishlistItems, books);

    private final Cart cart = mock(Cart.class);
    private final Map<Long, BookInfo> catalogue = new HashMap<>();
    private final List<CartItem> lines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(cart.getId()).thenReturn(CART_ID);
        when(carts.findByUserIdForUpdate(USER)).thenReturn(Optional.of(cart));
        when(cartItems.findByCartIdOrderByIdAsc(CART_ID)).thenAnswer(i -> new ArrayList<>(lines));
        when(cartItems.save(any(CartItem.class))).thenAnswer(i -> i.getArgument(0));
        when(wishlistItems.save(any(WishlistItem.class))).thenAnswer(i -> i.getArgument(0));
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

    // ---- moveToCart tests -----------------------------------------------------------------------------------

    @Test
    void moveToCartAcquiresCartLockThenWishlistAdvisoryLock() {
        catalogue.put(1L, activeBook(1L, "Book", 5, BigDecimal.TEN));
        WishlistItem wishlistItem = WishlistItem.create(USER, 1L);
        when(wishlistItems.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.of(wishlistItem));

        service.moveToCart(USER, 1L);

        InOrder inOrder = inOrder(carts, wishlistItems);
        inOrder.verify(carts).findByUserIdForUpdate(USER);
        inOrder.verify(wishlistItems).acquireAdvisoryLock(WishlistService.lockKey(USER));
    }

    @Test
    void moveToCartRejectsMissingWishlistEntryWith404() {
        when(wishlistItems.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveToCart(USER, 1L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(cartItems, never()).save(any());
        verify(wishlistItems, never()).delete(any());
    }

    @Test
    void moveToCartRejectsInactiveBookWith409BookUnavailable() {
        catalogue.put(1L, inactiveBook(1L, "Inactive Book"));
        WishlistItem wishlistItem = WishlistItem.create(USER, 1L);
        when(wishlistItems.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.of(wishlistItem));

        assertThatThrownBy(() -> service.moveToCart(USER, 1L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.BOOK_UNAVAILABLE));

        verify(cartItems, never()).save(any());
        verify(wishlistItems, never()).delete(any());
    }

    @Test
    void moveToCartAddsLineWithQty1WhenNotAlreadyInCartAndDeletesWishlistEntry() {
        catalogue.put(1L, activeBook(1L, "Active Book", 5, new BigDecimal("199.99")));
        WishlistItem wishlistItem = WishlistItem.create(USER, 1L);
        when(wishlistItems.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.of(wishlistItem));

        CartResponse response = service.moveToCart(USER, 1L);

        assertThat(response.lineCount()).isEqualTo(1);
        assertThat(response.itemCount()).isEqualTo(1);
        verify(cartItems).save(any(CartItem.class));
        verify(cart).touch();
        verify(wishlistItems).delete(wishlistItem);
    }

    @Test
    void moveToCartLeavesQuantityUnchangedWhenAlreadyInCartAndDeletesWishlistEntry() {
        catalogue.put(1L, activeBook(1L, "Active Book", 5, new BigDecimal("199.99")));
        CartItem existing = CartItem.create(CART_ID, 1L, 3);
        lines.add(existing);
        WishlistItem wishlistItem = WishlistItem.create(USER, 1L);
        when(wishlistItems.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.of(wishlistItem));

        CartResponse response = service.moveToCart(USER, 1L);

        assertThat(response.lineCount()).isEqualTo(1);
        assertThat(response.itemCount()).isEqualTo(3);
        verify(cartItems, never()).save(any());
        verify(wishlistItems).delete(wishlistItem);
    }

    @Test
    void moveToCartRejectsWhenCartFullAt50Lines() {
        catalogue.put(99L, activeBook(99L, "New Book", 5, BigDecimal.TEN));
        for (long i = 1; i <= 50; i++) {
            lines.add(CartItem.create(CART_ID, i, 1));
        }
        WishlistItem wishlistItem = WishlistItem.create(USER, 99L);
        when(wishlistItems.findByUserIdAndBookId(USER, 99L)).thenReturn(Optional.of(wishlistItem));

        assertThatThrownBy(() -> service.moveToCart(USER, 99L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CART_FULL));

        verify(cartItems, never()).save(any());
        verify(wishlistItems, never()).delete(any());
    }

    @Test
    void moveToCartRejectsWhenOutOfStock() {
        catalogue.put(1L, activeBook(1L, "Book", 0, BigDecimal.TEN));
        WishlistItem wishlistItem = WishlistItem.create(USER, 1L);
        when(wishlistItems.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.of(wishlistItem));

        assertThatThrownBy(() -> service.moveToCart(USER, 1L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

        verify(cartItems, never()).save(any());
        verify(wishlistItems, never()).delete(any());
    }

    // ---- moveToWishlist tests --------------------------------------------------------------------------------

    @Test
    void moveToWishlistAcquiresCartLockThenWishlistAdvisoryLock() {
        catalogue.put(1L, activeBook(1L, "Book", 5, BigDecimal.TEN));
        CartItem line = CartItem.create(CART_ID, 1L, 2);
        lines.add(line);
        when(wishlistItems.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.empty());

        service.moveToWishlist(USER, 1L);

        InOrder inOrder = inOrder(carts, wishlistItems);
        inOrder.verify(carts).findByUserIdForUpdate(USER);
        inOrder.verify(wishlistItems).acquireAdvisoryLock(WishlistService.lockKey(USER));
    }

    @Test
    void moveToWishlistRejectsWhenCartRowNotFoundWith404() {
        when(carts.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveToWishlist(USER, 1L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(wishlistItems, never()).save(any());
        verify(cartItems, never()).delete(any());
    }

    @Test
    void moveToWishlistRejectsWhenLineNotFoundInCartWith404() {
        assertThatThrownBy(() -> service.moveToWishlist(USER, 1L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(wishlistItems, never()).save(any());
        verify(cartItems, never()).delete(any());
    }

    @Test
    void moveToWishlistRejectsWhenBookInactiveWith409BookUnavailable() {
        catalogue.put(1L, inactiveBook(1L, "Inactive Book"));
        CartItem line = CartItem.create(CART_ID, 1L, 2);
        lines.add(line);

        assertThatThrownBy(() -> service.moveToWishlist(USER, 1L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.BOOK_UNAVAILABLE));

        verify(wishlistItems, never()).save(any());
        verify(cartItems, never()).delete(any());
    }

    @Test
    void moveToWishlistAddsToWishlistAndDeletesCartLine() {
        catalogue.put(1L, activeBook(1L, "Active Book", 5, new BigDecimal("199.99")));
        CartItem line = CartItem.create(CART_ID, 1L, 4);
        lines.add(line);
        when(wishlistItems.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.empty());
        when(wishlistItems.countByUserId(USER)).thenReturn(10L);

        CartResponse response = service.moveToWishlist(USER, 1L);

        assertThat(response.lineCount()).isZero();
        assertThat(response.itemCount()).isZero();
        verify(wishlistItems).save(any(WishlistItem.class));
        verify(cartItems).delete(line);
        verify(cart).touch();
    }

    @Test
    void moveToWishlistDoesNotDuplicateIfAlreadyInWishlistAndDeletesCartLine() {
        catalogue.put(1L, activeBook(1L, "Active Book", 5, new BigDecimal("199.99")));
        CartItem line = CartItem.create(CART_ID, 1L, 4);
        lines.add(line);
        WishlistItem existing = WishlistItem.create(USER, 1L);
        when(wishlistItems.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.of(existing));

        CartResponse response = service.moveToWishlist(USER, 1L);

        assertThat(response.lineCount()).isZero();
        verify(wishlistItems, never()).save(any());
        verify(cartItems).delete(line);
        verify(cart).touch();
    }

    @Test
    void moveToWishlistRejectsWhenWishlistFullAt200Items() {
        catalogue.put(1L, activeBook(1L, "Active Book", 5, new BigDecimal("199.99")));
        CartItem line = CartItem.create(CART_ID, 1L, 4);
        lines.add(line);
        when(wishlistItems.findByUserIdAndBookId(USER, 1L)).thenReturn(Optional.empty());
        when(wishlistItems.countByUserId(USER)).thenReturn(200L);

        assertThatThrownBy(() -> service.moveToWishlist(USER, 1L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.WISHLIST_FULL));

        verify(wishlistItems, never()).save(any());
        verify(cartItems, never()).delete(any());
    }
}
