package com.bookworm.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class OrderIT extends AbstractOrderIT {

    @Test
    void completeSuccessfulCheckoutDecrementsStockClearsCartAndPersistsEntities() throws Exception {
        long book1 = book("Book One", "299.00", 10, true);
        long book2 = book("Book Two", "199.50", 5, true);

        addToCart(customerToken, book1, 2);
        addToCart(customerToken, book2, 1);
        assertThat(cartLines(customerId)).isEqualTo(2);

        String idemKey = "idem-key-success-1";
        String body = validCheckoutJson("tok_success", 0);

        MvcResult result = checkout(customerToken, idemKey, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        String orderNumber = read(result, "$.orderNumber");
        assertThat(orderNumber).matches("^BW-\\d{8}-[A-Z0-9]{6}$");
        assertThat((String) read(result, "$.status")).isEqualTo("PLACED");
        assertThat(((Number) read(result, "$.subtotal")).doubleValue()).isEqualTo(797.50);
        assertThat(((Number) read(result, "$.total")).doubleValue()).isEqualTo(797.50);
        assertThat(((Number) read(result, "$.pointsEarned")).longValue()).isEqualTo(7L); // floor(797.50 / 100) = 7

        // Verify Cart is cleared
        assertThat(cartLines(customerId)).isEqualTo(0);

        // Verify Stock decremented
        assertThat(stockOf(book1)).isEqualTo(8);
        assertThat(stockOf(book2)).isEqualTo(4);

        // Verify Database persistence
        assertThat(orderCount(customerId)).isEqualTo(1);
        assertThat(orderItemsCount(orderNumber)).isEqualTo(2);
        assertThat(orderStatusHistoryCount(orderNumber)).isEqualTo(1);
        assertThat(paymentCount(orderNumber)).isEqualTo(1);

        // Verify GET order by order number
        MvcResult getResult = getOrderByNumber(customerToken, orderNumber);
        assertThat(getResult.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) read(getResult, "$.orderNumber")).isEqualTo(orderNumber);
        assertThat((String) read(getResult, "$.status")).isEqualTo("PLACED");
        assertThat(((Number) read(getResult, "$.items[0].bookId")).longValue()).isEqualTo(book1);
    }

    @Test
    void idempotencyReplayReturnsCachedOrderWithoutCreatingDuplicates() throws Exception {
        long book1 = book("Book One", "100.00", 10, true);
        addToCart(customerToken, book1, 1);

        String idemKey = "idem-key-replay-1";
        String body = validCheckoutJson("tok_success", 0);

        // First attempt -> 201 Created
        MvcResult result1 = checkout(customerToken, idemKey, body);
        assertThat(result1.getResponse().getStatus()).isEqualTo(201);
        String orderNumber = read(result1, "$.orderNumber");
        assertThat(result1.getResponse().getHeader("Idempotent-Replay")).isNull();

        // Second attempt with exact same key and payload -> 200 OK + Idempotent-Replay header
        MvcResult result2 = checkout(customerToken, idemKey, body);
        assertThat(result2.getResponse().getStatus()).isEqualTo(200);
        assertThat(result2.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat((String) read(result2, "$.orderNumber")).isEqualTo(orderNumber);

        // Verify no duplicate order or stock decrement
        assertThat(orderCount(customerId)).isEqualTo(1);
        assertThat(orderItemsCount(orderNumber)).isEqualTo(1);
        assertThat(stockOf(book1)).isEqualTo(9);
    }

    @Test
    void idempotencyKeyReusedWithDifferentPayloadThrows422() throws Exception {
        long book1 = book("Book One", "100.00", 10, true);
        addToCart(customerToken, book1, 1);

        String idemKey = "idem-key-conflict-1";
        String body1 = validCheckoutJson("tok_success", 0);
        String body2 = validCheckoutJson("tok_declined", 0);

        MvcResult result1 = checkout(customerToken, idemKey, body1);
        assertThat(result1.getResponse().getStatus()).isEqualTo(201);

        MvcResult result2 = checkout(customerToken, idemKey, body2);
        assertThat(result2.getResponse().getStatus()).isEqualTo(422);
        assertThat((String) read(result2, "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void checkoutWithRedeemPointsWhenBalanceIsZeroThrows422() throws Exception {
        long book1 = book("Book One", "500.00", 10, true);
        addToCart(customerToken, book1, 1);

        String body = validCheckoutJson("tok_success", 50);
        MvcResult result = checkout(customerToken, "idem-pts-1", body);

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat((String) read(result, "$.code")).isEqualTo("POINTS_LIMIT_EXCEEDED");
    }

    @Test
    void checkoutExceedingMaxPointsLimitThrows422() throws Exception {
        long book1 = book("Book One", "100.00", 10, true);
        addToCart(customerToken, book1, 1);

        // Subtotal = 100, max discount = 20% = 20 points. Attempting 25 points.
        String body = validCheckoutJson("tok_success", 25);
        MvcResult result = checkout(customerToken, "idem-pts-exceed", body);

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat((String) read(result, "$.code")).isEqualTo("POINTS_LIMIT_EXCEEDED");
        assertThat(cartLines(customerId)).isEqualTo(1); // Cart remains intact
        assertThat(stockOf(book1)).isEqualTo(10); // Stock untouched
    }

    @Test
    void paymentDeclinedRollsBackOrderAndPreservesCartAndStock() throws Exception {
        long book1 = book("Book One", "150.00", 5, true);
        addToCart(customerToken, book1, 2);

        String body = validCheckoutJson("tok_declined", 0);
        MvcResult result = checkout(customerToken, "idem-pay-declined", body);

        assertThat(result.getResponse().getStatus()).isEqualTo(402);
        assertThat((String) read(result, "$.code")).isEqualTo("PAYMENT_DECLINED");

        // Cart and stock preserved
        assertThat(cartLines(customerId)).isEqualTo(1);
        assertThat(stockOf(book1)).isEqualTo(5);
        assertThat(orderCount(customerId)).isEqualTo(0);
    }

    @Test
    void paymentUnavailableRollsBackOrderAndPreservesCartAndStock() throws Exception {
        long book1 = book("Book One", "150.00", 5, true);
        addToCart(customerToken, book1, 2);

        String body = validCheckoutJson("tok_error", 0);
        MvcResult result = checkout(customerToken, "idem-pay-err", body);

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat((String) read(result, "$.code")).isEqualTo("PAYMENT_UNAVAILABLE");

        // Cart and stock preserved
        assertThat(cartLines(customerId)).isEqualTo(1);
        assertThat(stockOf(book1)).isEqualTo(5);
        assertThat(orderCount(customerId)).isEqualTo(0);
    }

    @Test
    void checkoutWithEmptyCartThrows422() throws Exception {
        String body = validCheckoutJson("tok_success", 0);
        MvcResult result = checkout(customerToken, "idem-empty-cart", body);

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat((String) read(result, "$.code")).isEqualTo("CART_EMPTY");
    }

    @Test
    void checkoutWithInsufficientStockThrows409() throws Exception {
        long book1 = book("Book One", "200.00", 1, true);
        addToCart(customerToken, book1, 1);

        // Manually drop stock to 0 to simulate concurrency before checkout
        jdbc.update("update books set stock_quantity = 0 where id = ?", book1);

        String body = validCheckoutJson("tok_success", 0);
        MvcResult result = checkout(customerToken, "idem-stock-insufficient", body);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) read(result, "$.code")).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(orderCount(customerId)).isEqualTo(0);
    }

    @Test
    void checkoutWithInactiveBookThrows409() throws Exception {
        long book1 = book("Book One", "200.00", 5, true);
        addToCart(customerToken, book1, 1);

        // Deactivate book before checkout
        setBookActive(book1, false);

        String body = validCheckoutJson("tok_success", 0);
        MvcResult result = checkout(customerToken, "idem-book-inactive", body);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) read(result, "$.code")).isEqualTo("BOOK_UNAVAILABLE");
        assertThat(orderCount(customerId)).isEqualTo(0);
    }

    @Test
    void customerCannotAccessAnotherCustomersOrder() throws Exception {
        long book1 = book("Book One", "100.00", 10, true);
        addToCart(customerToken, book1, 1);

        MvcResult result = checkout(customerToken, "idem-cross-user", validCheckoutJson("tok_success", 0));
        String orderNumber = read(result, "$.orderNumber");

        // Other customer tries to read order -> 404 NOT_FOUND
        MvcResult otherGet = getOrderByNumber(otherCustomerToken, orderNumber);
        assertThat(otherGet.getResponse().getStatus()).isEqualTo(404);
        assertThat((String) read(otherGet, "$.code")).isEqualTo("NOT_FOUND");
    }
}
