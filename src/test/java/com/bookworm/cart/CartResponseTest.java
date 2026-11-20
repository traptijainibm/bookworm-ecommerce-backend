package com.bookworm.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookworm.catalogue.BookInfo;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Totals, availability and the minimal rendering of unavailable lines. Pure logic: no Spring, no database. */
class CartResponseTest {

    private static BookInfo book(long id, boolean active, int stock, String price) {
        return new BookInfo(id, "9789980000000", "Title " + id,
                List.of(new BookInfo.AuthorRef(3L, "Author")), new BookInfo.CategoryRef(1L, "Cat", "cat"),
                new BigDecimal(price), "https://img.example.test/c.jpg", active, stock);
    }

    private static Map<Long, BookInfo> map(BookInfo... books) {
        Map<Long, BookInfo> map = new HashMap<>();
        for (BookInfo book : books) {
            map.put(book.id(), book);
        }
        return map;
    }

    private static CartItem line(long bookId, int quantity) {
        return CartItem.create(1L, bookId, quantity);
    }

    @Test
    void threeCopiesOf199_99Total599_97ExactlyAtScaleTwo() {
        CartResponse response = CartResponse.from(List.of(line(1, 3)), map(book(1, true, 10, "199.99")));

        CartItemResponse item = response.items().get(0);
        assertThat(item.unitPrice()).isEqualTo(new BigDecimal("199.99"));
        assertThat(item.lineTotal()).isEqualTo(new BigDecimal("599.97"));
        assertThat(response.subtotal()).isEqualTo(new BigDecimal("599.97"));
        assertThat(response.itemCount()).isEqualTo(3);
        assertThat(response.lineCount()).isEqualTo(1);
        assertThat(response.checkoutReady()).isTrue();
    }

    @Test
    void manyLinesSumExactly() {
        List<CartItem> lines = new java.util.ArrayList<>();
        Map<Long, BookInfo> books = new HashMap<>();
        for (long id = 1; id <= 50; id++) {
            lines.add(line(id, 3));
            books.put(id, book(id, true, 10, "0.33"));
        }

        CartResponse response = CartResponse.from(lines, books);

        assertThat(response.subtotal()).isEqualTo(new BigDecimal("49.50"));
        assertThat(response.itemCount()).isEqualTo(150);
    }

    @Test
    void anEmptyCartHasAZeroSubtotalAtScaleTwoAndIsNotCheckoutReady() {
        CartResponse response = CartResponse.empty();

        assertThat(response.subtotal()).isEqualTo(new BigDecimal("0.00"));
        assertThat(response.checkoutReady()).isFalse();
        assertThat(CartResponse.from(List.of(), Map.of()).checkoutReady()).isFalse();
    }

    @Test
    void availabilityIsClassifiedFromStockAndQuantityWithoutExposingTheCount() {
        assertThat(CartItemResponse.availabilityOf(0, 1)).isEqualTo(ItemAvailability.OUT_OF_STOCK);
        assertThat(CartItemResponse.availabilityOf(2, 3)).isEqualTo(ItemAvailability.INSUFFICIENT_STOCK);
        assertThat(CartItemResponse.availabilityOf(3, 3)).isEqualTo(ItemAvailability.AVAILABLE);
        assertThat(CartItemResponse.availabilityOf(9, 3)).isEqualTo(ItemAvailability.AVAILABLE);
        assertThat(CartItemResponse.class.getRecordComponents()).extracting(RecordComponent::getName)
                .doesNotContain("stockQuantity", "stock", "active", "version");
    }

    @Test
    void outOfStockAndInsufficientStockLinesStillCountTowardTheSubtotalButBlockCheckoutReadiness() {
        CartResponse response = CartResponse.from(List.of(line(1, 2), line(2, 3), line(3, 1)),
                map(book(1, true, 0, "10.00"), book(2, true, 2, "20.00"), book(3, true, 5, "5.00")));

        assertThat(response.items()).extracting(CartItemResponse::availability).containsExactly(
                ItemAvailability.OUT_OF_STOCK, ItemAvailability.INSUFFICIENT_STOCK, ItemAvailability.AVAILABLE);
        assertThat(response.subtotal()).isEqualTo(new BigDecimal("85.00"));
        assertThat(response.itemCount()).isEqualTo(6);
        assertThat(response.checkoutReady()).isFalse();
    }

    @Test
    void anInactiveBooksLineIsMinimalAndExcludedFromTheTotals() {
        CartResponse response = CartResponse.from(List.of(line(1, 2), line(2, 4)),
                map(book(1, true, 10, "10.00"), book(2, false, 10, "99.00")));

        CartItemResponse gone = response.items().get(1);
        assertThat(gone.availability()).isEqualTo(ItemAvailability.UNAVAILABLE);
        assertThat(gone.bookId()).isEqualTo(2L);
        assertThat(gone.title()).isEqualTo("Title 2");
        assertThat(gone.isbn()).isNull();
        assertThat(gone.authors()).isNull();
        assertThat(gone.coverUrl()).isNull();
        assertThat(gone.unitPrice()).isNull();
        assertThat(gone.quantity()).isNull();
        assertThat(gone.lineTotal()).isNull();
        assertThat(response.lineCount()).isEqualTo(2);
        assertThat(response.itemCount()).isEqualTo(2);
        assertThat(response.subtotal()).isEqualTo(new BigDecimal("20.00"));
        assertThat(response.checkoutReady()).isFalse();
    }

    @Test
    void aLineWhoseBookRowIsMissingIsRenderedUnavailableRatherThanFailing() {
        CartResponse response = CartResponse.from(List.of(line(404, 1)), Map.of());

        assertThat(response.items().get(0).availability()).isEqualTo(ItemAvailability.UNAVAILABLE);
        assertThat(response.subtotal()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void linesKeepTheOrderTheyWereGiven() {
        CartResponse response = CartResponse.from(List.of(line(3, 1), line(1, 1), line(2, 1)),
                map(book(1, true, 5, "1.00"), book(2, true, 5, "1.00"), book(3, true, 5, "1.00")));

        assertThat(response.items()).extracting(CartItemResponse::bookId).containsExactly(3L, 1L, 2L);
    }

    @Test
    void thePriceIsTheLiveCataloguePriceAndNoPriceIsStoredOnALine() {
        assertThat(CartItem.class.getDeclaredFields()).extracting(java.lang.reflect.Field::getName)
                .noneMatch(name -> name.toLowerCase().contains("price"));
        CartResponse before = CartResponse.from(List.of(line(1, 1)), map(book(1, true, 5, "10.00")));
        CartResponse after = CartResponse.from(List.of(line(1, 1)), map(book(1, true, 5, "12.50")));

        assertThat(before.subtotal()).isEqualTo(new BigDecimal("10.00"));
        assertThat(after.subtotal()).isEqualTo(new BigDecimal("12.50"));
    }
}
