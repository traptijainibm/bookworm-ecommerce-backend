package com.bookworm.wishlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class WishlistIT extends AbstractWishlistIT {

    private static final String WISHLIST = "/api/v1/me/wishlist";

    @Test
    void addAndGetWishlistFlow() throws Exception {
        long book1 = book("Book One", "199.99", 5, true);
        long book2 = book("Book Two", "299.99", 0, true);

        // Add book 1
        mockMvc.perform(asCustomer(json(post(WISHLIST + "/items"), "{\"bookId\":" + book1 + "}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookId").value(book1))
                .andExpect(jsonPath("$.availability").value("AVAILABLE"));

        // Idempotent add of book 1 returns 200 OK
        mockMvc.perform(asCustomer(json(post(WISHLIST + "/items"), "{\"bookId\":" + book1 + "}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId").value(book1));

        // Add book 2 (out of stock)
        mockMvc.perform(asCustomer(json(post(WISHLIST + "/items"), "{\"bookId\":" + book2 + "}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookId").value(book2))
                .andExpect(jsonPath("$.availability").value("OUT_OF_STOCK"));

        assertThat(wishlistRows(customerId)).isEqualTo(2);

        // GET wishlist
        mockMvc.perform(asCustomer(get(WISHLIST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].bookId").value(book2))
                .andExpect(jsonPath("$.content[1].bookId").value(book1));
    }

    @Test
    void customerIsolationCustomerACannotSeeOrMutateCustomerBWishlist() throws Exception {
        long book = book("Shared Book", "100.00", 5, true);

        mockMvc.perform(asCustomer(json(post(WISHLIST + "/items"), "{\"bookId\":" + book + "}")))
                .andExpect(status().isCreated());

        // Other customer sees empty wishlist
        mockMvc.perform(asOther(get(WISHLIST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Other customer deleting book does not delete customer A's entry
        mockMvc.perform(asOther(delete(WISHLIST + "/items/" + book)))
                .andExpect(status().isNoContent());

        assertThat(hasWishlistItem(customerId, book)).isTrue();
    }

    @Test
    void inactiveBookHandling() throws Exception {
        long book = book("Soon Inactive", "100.00", 5, true);

        mockMvc.perform(asCustomer(json(post(WISHLIST + "/items"), "{\"bookId\":" + book + "}")))
                .andExpect(status().isCreated());

        // Deactivate book
        setBookActive(book, false);

        // GET renders it as UNAVAILABLE and keeps it
        mockMvc.perform(asCustomer(get(WISHLIST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].availability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.content[0].price").doesNotExist())
                .andExpect(jsonPath("$.content[0].authors").doesNotExist());

        // POST-add of held inactive book returns 404 NOT_FOUND
        mockMvc.perform(asCustomer(json(post(WISHLIST + "/items"), "{\"bookId\":" + book + "}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Move to cart of held inactive book returns 409 BOOK_UNAVAILABLE
        mockMvc.perform(asCustomer(post(WISHLIST + "/items/" + book + "/move-to-cart")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOK_UNAVAILABLE"));

        // DELETE of held inactive book succeeds with 204
        mockMvc.perform(asCustomer(delete(WISHLIST + "/items/" + book)))
                .andExpect(status().isNoContent());

        assertThat(hasWishlistItem(customerId, book)).isFalse();
    }

    @Test
    void moveToCartAndMoveToWishlistFlow() throws Exception {
        long book = book("Moving Book", "150.00", 5, true);

        // Add to wishlist
        mockMvc.perform(asCustomer(json(post(WISHLIST + "/items"), "{\"bookId\":" + book + "}")))
                .andExpect(status().isCreated());

        assertThat(hasWishlistItem(customerId, book)).isTrue();
        assertThat(lineRows(customerId)).isZero();

        // Move to cart
        mockMvc.perform(asCustomer(post(WISHLIST + "/items/" + book + "/move-to-cart")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(1))
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.items[0].bookId").value(book));

        assertThat(hasWishlistItem(customerId, book)).isFalse();
        assertThat(lineRows(customerId)).isEqualTo(1);
        assertThat(quantityOf(customerId, book)).isEqualTo(1);

        // Move back to wishlist
        mockMvc.perform(asCustomer(post("/api/v1/me/cart/items/" + book + "/move-to-wishlist")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(0))
                .andExpect(jsonPath("$.itemCount").value(0));

        assertThat(hasWishlistItem(customerId, book)).isTrue();
        assertThat(lineRows(customerId)).isZero();
    }

    @Test
    void movesRejectMissingSourceWith404() throws Exception {
        long book = book("Absent Book", "150.00", 5, true);

        // Move from wishlist to cart when absent in wishlist -> 404
        mockMvc.perform(asCustomer(post(WISHLIST + "/items/" + book + "/move-to-cart")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Move from cart to wishlist when absent in cart -> 404
        mockMvc.perform(asCustomer(post("/api/v1/me/cart/items/" + book + "/move-to-wishlist")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void deleteIsIdempotentAndNever404() throws Exception {
        mockMvc.perform(asCustomer(delete(WISHLIST + "/items/999999")))
                .andExpect(status().isNoContent());
    }
}
