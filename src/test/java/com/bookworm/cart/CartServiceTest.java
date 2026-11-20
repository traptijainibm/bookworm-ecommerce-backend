package com.bookworm.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

/** Cart business rules with mocked collaborators: no Spring context, no database. */
class CartServiceTest {

    private static final Long USER = 7L;
    private static final Long CART_ID = 5L;

    private final CartRepository carts = mock(CartRepository.class);
    private final CartItemRepository items = mock(CartItemRepository.class);
    private final BookLookupService books = mock(BookLookupService.class);
    private final CartService service = new CartService(carts, items, books);

    private final Cart cart = mock(Cart.class);
    private final Map<Long, BookInfo> catalogue = new HashMap<>();
    private final List<CartItem> lines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(cart.getId()).thenReturn(CART_ID);
        when(carts.findByUserIdForUpdate(USER)).thenReturn(Optional.of(cart));
        when(carts.findByUserId(USER)).thenReturn(Optional.of(cart));
        when(items.findByCartIdOrderByIdAsc(CART_ID)).thenAnswer(i -> new ArrayList<>(lines));
        when(items.save(any(CartItem.class))).thenAnswer(i -> i.getArgument(0));
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

    private void book(long id, boolean active, int stock, String price) {
        catalogue.put(id, new BookInfo(id, "9789980000" + String.format("%03d", id), "Title " + id, List.of(),
                new BookInfo.CategoryRef(1L, "Cat", "cat"), new BigDecimal(price), null, active, stock));
    }

    private CartItem line(long bookId, int quantity) {
        CartItem line = CartItem.create(CART_ID, bookId, quantity);
        lines.add(line);
        return line;
    }

    private static ApiException thrown(Runnable action) {
        return (ApiException) catchThrowable(action);
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("expected an ApiException");
    }

    // ---- GET -------------------------------------------------------------------------------------------

    @Test
    void getWithoutACartIsEmptyAndCreatesNothing() {
        when(carts.findByUserId(USER)).thenReturn(Optional.empty());

        CartResponse response = service.get(USER);

        assertThat(response.items()).isEmpty();
        assertThat(response.lineCount()).isZero();
        assertThat(response.itemCount()).isZero();
        assertThat(response.subtotal()).isEqualByComparingTo("0.00");
        assertThat(response.checkoutReady()).isFalse();
        verify(carts, never()).insertIfAbsent(anyLong());
        verify(carts, never()).findByUserIdForUpdate(anyLong());
        verify(items, never()).save(any());
    }

    @Test
    void getReadsWithoutTakingTheLockAndShowsLivePrices() {
        book(10, true, 5, "199.99");
        line(10, 3);

        CartResponse response = service.get(USER);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("199.99");
        assertThat(response.subtotal()).isEqualByComparingTo("599.97");
        verify(carts, never()).findByUserIdForUpdate(anyLong());
    }

    // ---- add ------------------------------------------------------------------------------------------

    @Test
    void addCreatesANewLineUnderTheCartLockAndTouchesTheCart() {
        book(10, true, 5, "199.99");

        CartResponse response = service.add(USER, 10L, 2);

        assertThat(response.lineCount()).isEqualTo(1);
        assertThat(response.itemCount()).isEqualTo(2);
        assertThat(response.subtotal()).isEqualByComparingTo("399.98");
        verify(items).save(any(CartItem.class));
        verify(cart).touch();
        verify(carts, never()).insertIfAbsent(anyLong());
    }

    @Test
    void addCreatesTheCartRowRaceFreeWhenTheCustomerHasNone() {
        book(10, true, 5, "10.00");
        when(carts.findByUserIdForUpdate(USER)).thenReturn(Optional.empty(), Optional.of(cart));

        service.add(USER, 10L, 1);

        InOrder order = inOrder(carts);
        order.verify(carts).findByUserIdForUpdate(USER);
        order.verify(carts).insertIfAbsent(USER);
        order.verify(carts).findByUserIdForUpdate(USER);
    }

    @Test
    void addTakesTheCartLockBeforeLookingAtTheBook() {
        book(10, true, 5, "10.00");

        service.add(USER, 10L, 1);

        InOrder order = inOrder(carts, books);
        order.verify(carts).findByUserIdForUpdate(USER);
        order.verify(books).findById(10L);
    }

    @Test
    void addMergesIntoTheExistingLineInsteadOfCreatingASecondOne() {
        book(10, true, 9, "10.00");
        CartItem existing = line(10, 2);

        CartResponse response = service.add(USER, 10L, 3);

        assertThat(existing.getQuantity()).isEqualTo(5);
        assertThat(response.lineCount()).isEqualTo(1);
        assertThat(response.itemCount()).isEqualTo(5);
        verify(items, never()).save(any());
    }

    @Test
    void addAllowsExactlyTenAndRefusesEleven() {
        book(10, true, 50, "10.00");
        CartItem existing = line(10, 7);

        service.add(USER, 10L, 3);
        assertThat(existing.getQuantity()).isEqualTo(10);

        ApiException ex = thrown(() -> service.add(USER, 10L, 1));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.QUANTITY_LIMIT_EXCEEDED);
        assertThat(existing.getQuantity()).isEqualTo(10);
    }

    @Test
    void addRefusesMoreThanTheCurrentStockAndAStockOfZeroWithoutRevealingTheCount() {
        book(10, true, 4, "10.00");
        book(11, true, 0, "10.00");

        ApiException over = thrown(() -> service.add(USER, 10L, 5));
        ApiException none = thrown(() -> service.add(USER, 11L, 1));

        assertThat(over.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(none.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(over.getMessage()).doesNotContain("4");
        verify(items, never()).save(any());
    }

    @Test
    void addAllowsAQuantityEqualToStock() {
        book(10, true, 4, "10.00");

        CartResponse response = service.add(USER, 10L, 4);

        assertThat(response.items().get(0).availability()).isEqualTo(ItemAvailability.AVAILABLE);
    }

    @Test
    void mergedQuantityIsCheckedAgainstStockNotJustTheRequest() {
        book(10, true, 4, "10.00");
        CartItem existing = line(10, 3);

        ApiException ex = thrown(() -> service.add(USER, 10L, 2));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(existing.getQuantity()).isEqualTo(3);
    }

    @Test
    void addOfAnUnknownBookIsNotFound() {
        ApiException ex = thrown(() -> service.add(USER, 999L, 1));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(items, never()).save(any());
    }

    @Test
    void addOfAnInactiveBookIsTheSameNotFoundAsAnUnknownOne() {
        book(10, false, 5, "10.00");

        ApiException inactive = thrown(() -> service.add(USER, 10L, 1));
        ApiException unknown = thrown(() -> service.add(USER, 999L, 1));

        assertThat(inactive.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(inactive.getMessage()).isEqualTo(unknown.getMessage());
    }

    @Test
    void addOfAnInactiveBookTheCallerAlreadyHoldsIsStillNotFoundAndChangesNothing() {
        book(10, false, 5, "10.00");
        CartItem existing = line(10, 2);

        ApiException ex = thrown(() -> service.add(USER, 10L, 1));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(existing.getQuantity()).isEqualTo(2);
    }

    @Test
    void theFiftyFirstDistinctLineIsRefusedButAnExistingLineStillMerges() {
        for (long id = 1; id <= 50; id++) {
            book(id, true, 20, "10.00");
            line(id, 1);
        }
        book(99, true, 20, "10.00");

        ApiException ex = thrown(() -> service.add(USER, 99L, 1));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CART_FULL);
        verify(items, never()).save(any());

        CartResponse merged = service.add(USER, 1L, 1);
        assertThat(merged.lineCount()).isEqualTo(50);
        assertThat(merged.itemCount()).isEqualTo(51);
    }

    @Test
    void checksRunInTheApprovedOrder() {
        // inactive (404) before line limit; line limit before quantity limit; quantity limit before stock.
        for (long id = 1; id <= 50; id++) {
            book(id, true, 20, "10.00");
            line(id, 1);
        }
        book(98, false, 0, "10.00");
        book(99, true, 0, "10.00");
        assertThat(thrown(() -> service.add(USER, 98L, 1)).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(thrown(() -> service.add(USER, 99L, 1)).getErrorCode()).isEqualTo(ErrorCode.CART_FULL);

        lines.clear();
        book(10, true, 1, "10.00");
        line(10, 8);
        // merged 13 is above 10 and above stock 1: the quantity limit is reported first.
        assertThat(thrown(() -> service.add(USER, 10L, 5)).getErrorCode())
                .isEqualTo(ErrorCode.QUANTITY_LIMIT_EXCEEDED);
    }

    // ---- set quantity -----------------------------------------------------------------------------------

    @Test
    void setQuantityWithoutACartIsNotFoundAndNeverLooksAtTheBook() {
        when(carts.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());

        ApiException ex = thrown(() -> service.setQuantity(USER, 10L, 2));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verifyNoInteractions(books);
        verify(carts, never()).insertIfAbsent(anyLong());
    }

    @Test
    void setQuantityOnAnAbsentLineIsNotFoundBeforeTheBookIsExamined() {
        book(10, false, 0, "10.00");

        ApiException ex = thrown(() -> service.setQuantity(USER, 10L, 2));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(books, never()).findById(any());
    }

    @Test
    void setQuantityOnAnInactiveBooksLineIsBookUnavailable() {
        book(10, false, 5, "10.00");
        CartItem existing = line(10, 2);

        ApiException ex = thrown(() -> service.setQuantity(USER, 10L, 3));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BOOK_UNAVAILABLE);
        assertThat(existing.getQuantity()).isEqualTo(2);
    }

    @Test
    void setQuantityAboveStockIsInsufficientStock() {
        book(10, true, 3, "10.00");
        CartItem existing = line(10, 2);

        ApiException ex = thrown(() -> service.setQuantity(USER, 10L, 4));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(existing.getQuantity()).isEqualTo(2);
    }

    @Test
    void setQuantitySetsTheAbsoluteValueAndIsIdempotent() {
        book(10, true, 9, "10.00");
        CartItem existing = line(10, 2);

        service.setQuantity(USER, 10L, 6);
        CartResponse again = service.setQuantity(USER, 10L, 6);

        assertThat(existing.getQuantity()).isEqualTo(6);
        assertThat(again.itemCount()).isEqualTo(6);
        verify(items, never()).save(any());
    }

    // ---- remove / clear ---------------------------------------------------------------------------------

    @Test
    void removeDeletesThePresentLineAndNeverExaminesTheBookState() {
        book(10, false, 0, "10.00");
        book(11, true, 5, "10.00");
        CartItem inactiveLine = line(10, 2);
        line(11, 1);

        CartResponse response = service.remove(USER, 10L);

        verify(items).delete(inactiveLine);
        verify(cart).touch();
        assertThat(response.lineCount()).isEqualTo(1);
        verify(books, never()).findById(any());
    }

    @Test
    void removeOfAnAbsentLineSucceedsAndChangesNothing() {
        book(11, true, 5, "10.00");
        line(11, 1);

        CartResponse response = service.remove(USER, 10L);

        assertThat(response.lineCount()).isEqualTo(1);
        verify(items, never()).delete(any());
        verify(cart, never()).touch();
    }

    @Test
    void removeAndClearWithoutACartSucceedWithAnEmptyCartAndCreateNothing() {
        when(carts.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());

        assertThat(service.remove(USER, 10L).items()).isEmpty();
        assertThat(service.clear(USER).items()).isEmpty();

        verify(carts, never()).insertIfAbsent(anyLong());
        verify(items, never()).delete(any());
        verify(items, never()).deleteAll(any());
    }

    @Test
    void clearRemovesEveryLineAndKeepsTheCartRow() {
        book(10, true, 5, "10.00");
        book(11, false, 5, "10.00");
        line(10, 1);
        line(11, 1);

        CartResponse response = service.clear(USER);

        verify(items).deleteAll(any());
        verify(carts, never()).delete(any());
        verify(cart).touch();
        assertThat(response.items()).isEmpty();
        assertThat(response.subtotal()).isEqualByComparingTo("0.00");
    }

    @Test
    void clearOfAnEmptyCartSucceedsWithoutWriting() {
        CartResponse response = service.clear(USER);

        assertThat(response.items()).isEmpty();
        verify(items, never()).deleteAll(any());
        verify(cart, never()).touch();
    }

    // ---- structure ----------------------------------------------------------------------------------------

    @Test
    void theServiceDependsOnlyOnTheCartRepositoriesAndTheReadOnlyBookLookup() {
        assertThat(CartService.class.getDeclaredFields())
                .filteredOn(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .extracting(f -> f.getType().getSimpleName())
                .containsExactlyInAnyOrder("CartRepository", "CartItemRepository", "BookLookupService");
    }

    @Test
    void noStockMessageContainsADigit() {
        book(12, true, 3, "10.00");
        book(13, true, 3, "10.00");
        line(13, 2);

        ApiException add = thrown(() -> service.add(USER, 12L, 4));
        ApiException merged = thrown(() -> service.add(USER, 13L, 2));
        ApiException put = thrown(() -> service.setQuantity(USER, 13L, 4));

        for (ApiException ex : List.of(add, merged, put)) {
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(ex.getMessage()).doesNotContainPattern("[0-9]");
        }
    }
}
