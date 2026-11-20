package com.bookworm.wishlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookworm.cart.ItemAvailability;
import com.bookworm.catalogue.BookInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WishlistItemResponseTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void activeItemIncludesAllFieldsAndSerializesCorrectly() throws Exception {
        BookInfo book = new BookInfo(12L, "9780000000012", "The Hobbit",
                List.of(new BookInfo.AuthorRef(3L, "J.R.R. Tolkien")),
                new BookInfo.CategoryRef(4L, "Fantasy", "fantasy"),
                new BigDecimal("199.99"), "https://cover/12.jpg", true, 5);

        WishlistItem item = WishlistItem.create(8L, 12L);
        WishlistItemResponse response = WishlistItemResponse.of(item, book);

        assertThat(response.bookId()).isEqualTo(12L);
        assertThat(response.isbn()).isEqualTo("9780000000012");
        assertThat(response.title()).isEqualTo("The Hobbit");
        assertThat(response.authors()).hasSize(1);
        assertThat(response.category().slug()).isEqualTo("fantasy");
        assertThat(response.price()).isEqualByComparingTo("199.99");
        assertThat(response.coverUrl()).isEqualTo("https://cover/12.jpg");
        assertThat(response.availability()).isEqualTo(ItemAvailability.AVAILABLE);
        assertThat(response.addedAt()).isNotNull();

        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"bookId\":12")
                .contains("\"isbn\":\"9780000000012\"")
                .contains("\"title\":\"The Hobbit\"")
                .contains("\"price\":199.99")
                .contains("\"availability\":\"AVAILABLE\"")
                .contains("\"addedAt\":");
    }

    @Test
    void outOfStockActiveItemHasOutOfStockAvailability() {
        BookInfo book = new BookInfo(12L, "9780000000012", "The Hobbit",
                List.of(new BookInfo.AuthorRef(3L, "J.R.R. Tolkien")),
                new BookInfo.CategoryRef(4L, "Fantasy", "fantasy"),
                new BigDecimal("199.99"), "https://cover/12.jpg", true, 0);

        WishlistItem item = WishlistItem.create(8L, 12L);
        WishlistItemResponse response = WishlistItemResponse.of(item, book);

        assertThat(response.availability()).isEqualTo(ItemAvailability.OUT_OF_STOCK);
    }

    @Test
    void unavailableItemOmitsNullFieldsFromSerialization() throws Exception {
        WishlistItemResponse response = WishlistItemResponse.unavailable(15L, "Deactivated Book");

        assertThat(response.bookId()).isEqualTo(15L);
        assertThat(response.title()).isEqualTo("Deactivated Book");
        assertThat(response.availability()).isEqualTo(ItemAvailability.UNAVAILABLE);
        assertThat(response.isbn()).isNull();
        assertThat(response.authors()).isNull();
        assertThat(response.category()).isNull();
        assertThat(response.price()).isNull();
        assertThat(response.coverUrl()).isNull();
        assertThat(response.addedAt()).isNull();

        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"bookId\":15")
                .contains("\"title\":\"Deactivated Book\"")
                .contains("\"availability\":\"UNAVAILABLE\"")
                .doesNotContain("isbn")
                .doesNotContain("authors")
                .doesNotContain("category")
                .doesNotContain("price")
                .doesNotContain("coverUrl")
                .doesNotContain("addedAt");
    }
}
