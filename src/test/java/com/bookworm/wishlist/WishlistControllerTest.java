package com.bookworm.wishlist;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookworm.cart.CartResponse;
import com.bookworm.cart.ItemAvailability;
import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import com.bookworm.common.error.GlobalExceptionHandler;
import com.bookworm.common.web.PageResponse;
import com.bookworm.common.web.StrictJsonConfig;
import com.bookworm.common.web.TraceIdFilter;
import com.bookworm.testsupport.WebSliceSecurity;
import com.bookworm.user.UserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest({WishlistController.class, WishlistMoveController.class})
@Import({GlobalExceptionHandler.class, TraceIdFilter.class, StrictJsonConfig.class})
@WebSliceSecurity
class WishlistControllerTest {

    private static final String WISHLIST = "/api/v1/me/wishlist";
    private static final String CART_MOVE = "/api/v1/me/cart/items/1/move-to-wishlist";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WishlistService wishlistService;

    @MockitoBean
    private WishlistMoveService wishlistMoveService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("8")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("7")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static WishlistItemResponse sampleItem(Long bookId) {
        return new WishlistItemResponse(bookId, "9780000000001", "Book One",
                List.of(new WishlistItemResponse.Author(1L, "Author")),
                new WishlistItemResponse.Category(2L, "Category", "category"),
                new BigDecimal("199.99"), "https://cover/1.jpg", ItemAvailability.AVAILABLE, Instant.now());
    }

    private static CartResponse emptyCart() {
        return new CartResponse(List.of(), 0, 0, new BigDecimal("0.00"), false);
    }

    // ---- authentication and authorization on every route -------------------------------------------------

    private static final List<String[]> ROUTES = List.of(
            new String[] {"GET", WISHLIST},
            new String[] {"POST", WISHLIST + "/items"},
            new String[] {"DELETE", WISHLIST + "/items/1"},
            new String[] {"POST", WISHLIST + "/items/1/move-to-cart"},
            new String[] {"POST", CART_MOVE});

    private static MockHttpServletRequestBuilder request(String method, String path) {
        return switch (method) {
            case "POST" -> post(path);
            case "DELETE" -> delete(path);
            default -> get(path);
        };
    }

    @Test
    void everyWishlistRouteRejectsAnonymousCallersWith401() throws Exception {
        for (String[] route : ROUTES) {
            mockMvc.perform(json(request(route[0], route[1]), "{\"bookId\":1}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(header().exists("X-Trace-Id"));
        }
    }

    @Test
    void everyWishlistRouteRejectsAdminWith403() throws Exception {
        for (String[] route : ROUTES) {
            mockMvc.perform(json(request(route[0], route[1]), "{\"bookId\":1}").with(admin()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    .andExpect(header().exists("X-Trace-Id"));
        }
    }

    // ---- GET /api/v1/me/wishlist ----------------------------------------------------------------------------

    @Test
    void getWishlistReturns200WithPageResponse() throws Exception {
        when(wishlistService.get(eq(8L), anyInt(), anyInt(), anyString()))
                .thenReturn(new PageResponse<>(List.of(sampleItem(1L)), 0, 20, 1, 1));

        mockMvc.perform(get(WISHLIST).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].bookId").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getWishlistRejectsInvalidSort() throws Exception {
        mockMvc.perform(get(WISHLIST).param("sort", "title").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void getWishlistRejectsNegativePage() throws Exception {
        mockMvc.perform(get(WISHLIST).param("page", "-1").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void getWishlistRejectsSizeAbove100() throws Exception {
        mockMvc.perform(get(WISHLIST).param("size", "101").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- POST /api/v1/me/wishlist/items ---------------------------------------------------------------------

    @Test
    void addReturns201CreatedWhenNew() throws Exception {
        when(wishlistService.add(8L, 12L)).thenReturn(new WishlistService.AddResult(sampleItem(12L), true));

        mockMvc.perform(json(post(WISHLIST + "/items"), "{\"bookId\":12}").with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookId").value(12));
    }

    @Test
    void addReturns200OkWhenAlreadyPresent() throws Exception {
        when(wishlistService.add(8L, 12L)).thenReturn(new WishlistService.AddResult(sampleItem(12L), false));

        mockMvc.perform(json(post(WISHLIST + "/items"), "{\"bookId\":12}").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId").value(12));
    }

    @Test
    void addRejectsMissingBookId() throws Exception {
        mockMvc.perform(json(post(WISHLIST + "/items"), "{}").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void addRejectsInvalidBookId() throws Exception {
        mockMvc.perform(json(post(WISHLIST + "/items"), "{\"bookId\":0}").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void addRejectsStringOrFloatCoercion() throws Exception {
        mockMvc.perform(json(post(WISHLIST + "/items"), "{\"bookId\":\"12\"}").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        mockMvc.perform(json(post(WISHLIST + "/items"), "{\"bookId\":12.5}").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void addRejectsUnknownFields() throws Exception {
        mockMvc.perform(json(post(WISHLIST + "/items"), "{\"bookId\":12,\"price\":100}").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ---- DELETE /api/v1/me/wishlist/items/{bookId} -----------------------------------------------------------

    @Test
    void deleteReturns204NoContent() throws Exception {
        mockMvc.perform(delete(WISHLIST + "/items/12").with(customer()))
                .andExpect(status().isNoContent());

        verify(wishlistService).remove(8L, 12L);
    }

    @Test
    void deleteRejectsInvalidBookId() throws Exception {
        mockMvc.perform(delete(WISHLIST + "/items/0").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- Move endpoints -------------------------------------------------------------------------------------

    @Test
    void moveToCartReturns200WithCartResponse() throws Exception {
        when(wishlistMoveService.moveToCart(8L, 12L)).thenReturn(emptyCart());

        mockMvc.perform(post(WISHLIST + "/items/12/move-to-cart").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(0));
    }

    @Test
    void moveToWishlistReturns200WithCartResponse() throws Exception {
        when(wishlistMoveService.moveToWishlist(8L, 12L)).thenReturn(emptyCart());

        mockMvc.perform(post("/api/v1/me/cart/items/12/move-to-wishlist").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(0));
    }

    // ---- Service Error Code Mappings ------------------------------------------------------------------------

    @Test
    void serviceErrorsMapToStandardCodes() throws Exception {
        when(wishlistService.add(8L, 99L))
                .thenThrow(new ApiException(ErrorCode.NOT_FOUND, "Book not found."));
        mockMvc.perform(json(post(WISHLIST + "/items"), "{\"bookId\":99}").with(customer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        when(wishlistService.add(8L, 100L))
                .thenThrow(new ApiException(ErrorCode.WISHLIST_FULL, "Wishlist full."));
        mockMvc.perform(json(post(WISHLIST + "/items"), "{\"bookId\":100}").with(customer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WISHLIST_FULL"));

        when(wishlistMoveService.moveToCart(8L, 101L))
                .thenThrow(new ApiException(ErrorCode.BOOK_UNAVAILABLE, "Book unavailable."));
        mockMvc.perform(post(WISHLIST + "/items/101/move-to-cart").with(customer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOK_UNAVAILABLE"));

        when(wishlistMoveService.moveToCart(8L, 102L))
                .thenThrow(new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock."));
        mockMvc.perform(post(WISHLIST + "/items/102/move-to-cart").with(customer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        when(wishlistMoveService.moveToCart(8L, 103L))
                .thenThrow(new ApiException(ErrorCode.CART_FULL, "Cart full."));
        mockMvc.perform(post(WISHLIST + "/items/103/move-to-cart").with(customer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CART_FULL"));
    }
}
