package com.bookworm.cart;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import com.bookworm.common.error.GlobalExceptionHandler;
import com.bookworm.common.web.StrictJsonConfig;
import com.bookworm.common.web.TraceIdFilter;
import com.bookworm.testsupport.WebSliceSecurity;
import com.bookworm.user.UserService;
import java.math.BigDecimal;
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

/** Web-layer behaviour of the cart API: security, validation, strict JSON, token-only identity, error mapping. */
@WebMvcTest(CartController.class)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class, StrictJsonConfig.class})
@WebSliceSecurity
class CartControllerTest {

    private static final String CART = "/api/v1/me/cart";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

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

    private static CartResponse cart() {
        return new CartResponse(List.of(), 0, 0, new BigDecimal("0.00"), false);
    }

    // ---- authentication and authorization on every route -------------------------------------------------

    private static final List<String[]> ROUTES = List.of(
            new String[] {"GET", CART},
            new String[] {"POST", CART + "/items"},
            new String[] {"PUT", CART + "/items/1"},
            new String[] {"DELETE", CART + "/items/1"},
            new String[] {"DELETE", CART + "/items"});

    private static MockHttpServletRequestBuilder request(String method, String path) {
        return switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> get(path);
        };
    }

    @Test
    void everyCartRouteRejectsAnonymousCallersWith401() throws Exception {
        for (String[] route : ROUTES) {
            mockMvc.perform(json(request(route[0], route[1]), "{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(cartService);
    }

    @Test
    void everyCartRouteRejectsAnAdminWith403() throws Exception {
        for (String[] route : ROUTES) {
            mockMvc.perform(json(request(route[0], route[1]), "{}").with(admin()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        verifyNoInteractions(cartService);
    }

    @Test
    void aCustomerReachesEveryRoute() throws Exception {
        when(cartService.get(8L)).thenReturn(cart());
        when(cartService.add(eq(8L), anyLong(), anyInt())).thenReturn(cart());
        when(cartService.setQuantity(eq(8L), anyLong(), anyInt())).thenReturn(cart());
        when(cartService.remove(eq(8L), anyLong())).thenReturn(cart());
        when(cartService.clear(8L)).thenReturn(cart());

        mockMvc.perform(get(CART).with(customer())).andExpect(status().isOk());
        mockMvc.perform(json(post(CART + "/items"), "{\"bookId\":1,\"quantity\":2}").with(customer()))
                .andExpect(status().isOk());
        mockMvc.perform(json(put(CART + "/items/1"), "{\"quantity\":3}").with(customer()))
                .andExpect(status().isOk());
        mockMvc.perform(delete(CART + "/items/1").with(customer())).andExpect(status().isOk());
        mockMvc.perform(delete(CART + "/items").with(customer())).andExpect(status().isOk());
    }

    @Test
    void thereIsNoPatchOrOtherMethodOnTheCartRoutes() throws Exception {
        mockMvc.perform(patch(CART + "/items/1").with(customer())).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(CART).with(customer())).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post(CART + "/items/1").with(customer())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(cartService);
    }

    // ---- identity comes from the token only ---------------------------------------------------------------

    @Test
    void theUserIsTheTokenSubjectAndNeverAQueryParameterOrBodyField() throws Exception {
        when(cartService.get(8L)).thenReturn(cart());

        mockMvc.perform(get(CART).param("userId", "99").param("user", "99").with(customer()))
                .andExpect(status().isOk());

        verify(cartService).get(8L);
    }

    @Test
    void aUserIdOrPriceInTheBodyIsRejectedAsMalformed() throws Exception {
        for (String extra : List.of("\"userId\":9", "\"price\":1", "\"cartId\":3", "\"unitPrice\":0.01")) {
            mockMvc.perform(json(post(CART + "/items"), "{\"bookId\":1,\"quantity\":2," + extra + "}")
                            .with(customer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        verifyNoInteractions(cartService);
    }

    // ---- body validation -----------------------------------------------------------------------------------

    @Test
    void addRejectsEveryInvalidQuantityAndBookId() throws Exception {
        List<String> validationFailures = List.of(
                "{\"bookId\":1,\"quantity\":0}", "{\"bookId\":1,\"quantity\":11}", "{\"bookId\":1,\"quantity\":-1}",
                "{\"bookId\":0,\"quantity\":1}", "{\"bookId\":-5,\"quantity\":1}", "{\"bookId\":1}",
                "{\"quantity\":1}", "{}", "{\"bookId\":1,\"quantity\":null}", "{\"bookId\":null,\"quantity\":1}");
        for (String body : validationFailures) {
            mockMvc.perform(json(post(CART + "/items"), body).with(customer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        List<String> malformed = List.of(
                "{\"bookId\":1,\"quantity\":\"2\"}", "{\"bookId\":1,\"quantity\":2.7}",
                "{\"bookId\":1,\"quantity\":2.0}", "{\"bookId\":1,\"quantity\":1e1}",
                "{\"bookId\":1,\"quantity\":true}", "{\"bookId\":1,\"quantity\":[2]}",
                "{\"bookId\":1,\"quantity\":{}}", "{\"bookId\":1,\"quantity\":\"\"}",
                "{\"bookId\":\"1\",\"quantity\":1}", "{\"bookId\":1.5,\"quantity\":1}",
                "{\"bookId\":1,\"quantity\":3000000000}", "{\"bookId\":99999999999999999999,\"quantity\":1}",
                "not json", "[]", "");
        for (String body : malformed) {
            mockMvc.perform(json(post(CART + "/items"), body).with(customer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        verifyNoInteractions(cartService);
    }

    @Test
    void putRejectsEveryInvalidQuantity() throws Exception {
        for (String body : List.of("{\"quantity\":0}", "{\"quantity\":11}", "{\"quantity\":-3}", "{}",
                "{\"quantity\":null}")) {
            mockMvc.perform(json(put(CART + "/items/1"), body).with(customer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        for (String body : List.of("{\"quantity\":\"2\"}", "{\"quantity\":2.5}", "{\"quantity\":2.0}",
                "{\"quantity\":true}", "{\"quantity\":2,\"bookId\":1}", "{\"quantity\":2,\"userId\":1}")) {
            mockMvc.perform(json(put(CART + "/items/1"), body).with(customer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        verifyNoInteractions(cartService);
    }

    @Test
    void malformedErrorsNeverEchoTheSubmittedValue() throws Exception {
        mockMvc.perform(json(post(CART + "/items"), "{\"bookId\":1,\"quantity\":\"zq-echo-4417\"}")
                        .with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", not(containsString("zq-echo"))));
    }

    @Test
    void anAbsentOrWrongContentTypeIsRejected() throws Exception {
        mockMvc.perform(post(CART + "/items").content("{\"bookId\":1,\"quantity\":1}").with(customer()))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post(CART + "/items").contentType(MediaType.TEXT_PLAIN)
                        .content("{\"bookId\":1,\"quantity\":1}").with(customer()))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(cartService);
    }

    // ---- path variables --------------------------------------------------------------------------------------

    @Test
    void aBadBookIdInThePathIsA400AndNeverReachesTheService() throws Exception {
        for (String id : List.of("0", "-1", "abc", "1.5")) {
            mockMvc.perform(json(put(CART + "/items/" + id), "{\"quantity\":1}").with(customer()))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(delete(CART + "/items/" + id).with(customer())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(cartService);
    }

    // ---- error mapping -----------------------------------------------------------------------------------------

    @Test
    void serviceErrorsKeepTheirStatusAndStableCode() throws Exception {
        when(cartService.add(eq(8L), eq(1L), anyInt()))
                .thenThrow(new ApiException(ErrorCode.NOT_FOUND, "Book not found."));
        when(cartService.add(eq(8L), eq(2L), anyInt()))
                .thenThrow(new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock is available for the requested quantity."));
        when(cartService.add(eq(8L), eq(3L), anyInt()))
                .thenThrow(new ApiException(ErrorCode.QUANTITY_LIMIT_EXCEEDED, "A cart line cannot hold more than 10 copies."));
        when(cartService.add(eq(8L), eq(4L), anyInt()))
                .thenThrow(new ApiException(ErrorCode.CART_FULL, "The cart cannot hold more than 50 different books."));
        when(cartService.setQuantity(eq(8L), eq(5L), anyInt()))
                .thenThrow(new ApiException(ErrorCode.BOOK_UNAVAILABLE, "This book is no longer available."));

        expect(post(CART + "/items"), "{\"bookId\":1,\"quantity\":1}", 404, "NOT_FOUND");
        expect(post(CART + "/items"), "{\"bookId\":2,\"quantity\":1}", 409, "INSUFFICIENT_STOCK");
        expect(post(CART + "/items"), "{\"bookId\":3,\"quantity\":1}", 422, "QUANTITY_LIMIT_EXCEEDED");
        expect(post(CART + "/items"), "{\"bookId\":4,\"quantity\":1}", 422, "CART_FULL");
        expect(put(CART + "/items/5"), "{\"quantity\":1}", 409, "BOOK_UNAVAILABLE");
    }

    private void expect(MockHttpServletRequestBuilder request, String body, int status, String code)
            throws Exception {
        mockMvc.perform(json(request, body).with(customer()))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    // ---- response shape ----------------------------------------------------------------------------------------

    @Test
    void anUnavailableLineIsRenderedMinimallyWithoutNullFields() throws Exception {
        CartItemResponse gone = CartItemResponse.unavailable(15L, "Old Title");
        when(cartService.get(8L)).thenReturn(new CartResponse(List.of(gone), 1, 0, new BigDecimal("0.00"), false));

        mockMvc.perform(get(CART).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].bookId").value(15))
                .andExpect(jsonPath("$.items[0].title").value("Old Title"))
                .andExpect(jsonPath("$.items[0].availability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.items[0].unitPrice").doesNotExist())
                .andExpect(jsonPath("$.items[0].quantity").doesNotExist())
                .andExpect(jsonPath("$.items[0].isbn").doesNotExist())
                .andExpect(jsonPath("$.items[0].authors").doesNotExist())
                .andExpect(jsonPath("$.items[0].coverUrl").doesNotExist())
                .andExpect(jsonPath("$.items[0].lineTotal").doesNotExist())
                .andExpect(jsonPath("$.items[0].stockQuantity").doesNotExist())
                .andExpect(jsonPath("$.subtotal").value(0.0))
                .andExpect(jsonPath("$.checkoutReady").value(false));
    }

    @Test
    void anAvailableLineCarriesLiveDataAndNeverTheStockCount() throws Exception {
        CartItemResponse available = new CartItemResponse(12L, "9789980000012", "Dune",
                List.of(new CartItemResponse.Author(3L, "Frank")), "https://img.example.test/d.jpg",
                new BigDecimal("199.99"), 3, new BigDecimal("599.97"), ItemAvailability.AVAILABLE);
        when(cartService.get(8L)).thenReturn(
                new CartResponse(List.of(available), 1, 3, new BigDecimal("599.97"), true));

        mockMvc.perform(get(CART).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(199.99))
                .andExpect(jsonPath("$.items[0].lineTotal").value(599.97))
                .andExpect(jsonPath("$.items[0].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.items[0].stockQuantity").doesNotExist())
                .andExpect(jsonPath("$.items[0].stock").doesNotExist())
                .andExpect(jsonPath("$.lineCount").value(1))
                .andExpect(jsonPath("$.itemCount").value(3))
                .andExpect(jsonPath("$.subtotal").value(599.97))
                .andExpect(jsonPath("$.checkoutReady").value(true));
    }
}
