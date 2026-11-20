package com.bookworm.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/**
 * Web-layer slice test for OrderController: security (CUSTOMER-only), request validation,
 * strict JSON deserialization, idempotency headers, and service error code mapping.
 */
@WebMvcTest(OrderController.class)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class, StrictJsonConfig.class})
@WebSliceSecurity
class OrderControllerTest {

    private static final String ORDERS = "/api/v1/me/orders";
    private static final String ORDER_NUMBER = "BW-2026-000001";
    private static final String IDEMPOTENCY_KEY = "idem-test-key-12345";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckoutService checkoutService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("8").claim("sub", "8"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("7").claim("sub", "7"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static ShippingAddressDto sampleAddress() {
        return new ShippingAddressDto(
                "Jane Doe",
                "9876543210",
                "123 Main St",
                "Apt 4B",
                "Bengaluru",
                "Karnataka",
                "560001",
                "India");
    }

    private static OrderResponse sampleOrderResponse() {
        OrderItemResponse item = new OrderItemResponse(
                10L,
                "9780000000001",
                "Sample Book",
                new BigDecimal("299.99"),
                2,
                new BigDecimal("599.98"));

        return new OrderResponse(
                ORDER_NUMBER,
                OrderStatus.PLACED,
                new BigDecimal("599.98"),
                100L,
                new BigDecimal("100.00"),
                new BigDecimal("499.98"),
                50L,
                sampleAddress(),
                List.of(item),
                List.of(),
                Instant.parse("2026-11-20T12:00:00Z"));
    }

    private static String validCheckoutJson() {
        return """
                {
                  "shippingAddress": {
                    "fullName": "Jane Doe",
                    "phone": "9876543210",
                    "addressLine1": "123 Main St",
                    "addressLine2": "Apt 4B",
                    "city": "Bengaluru",
                    "state": "Karnataka",
                    "postalCode": "560001",
                    "country": "India"
                  },
                  "paymentToken": "tok_success",
                  "redeemPoints": 100
                }
                """;
    }

    // ---- authentication and authorization on every route -------------------------------------------------

    private static final List<String[]> ROUTES = List.of(
            new String[] {"POST", ORDERS},
            new String[] {"GET", ORDERS + "/" + ORDER_NUMBER});

    private static MockHttpServletRequestBuilder routeRequest(String method, String path) {
        return "POST".equals(method) ? post(path) : get(path);
    }

    @Test
    void everyOrderRouteRejectsAnonymousCallersWith401() throws Exception {
        for (String[] route : ROUTES) {
            mockMvc.perform(json(routeRequest(route[0], route[1]), validCheckoutJson()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(header().exists("X-Trace-Id"));
        }
        verifyNoInteractions(checkoutService);
    }

    @Test
    void everyOrderRouteRejectsAdminWith403() throws Exception {
        for (String[] route : ROUTES) {
            mockMvc.perform(json(routeRequest(route[0], route[1]), validCheckoutJson()).with(admin()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    .andExpect(header().exists("X-Trace-Id"));
        }
        verifyNoInteractions(checkoutService);
    }

    @Test
    void unsupportedMethodsOnOrdersAreRejectedWith405() throws Exception {
        mockMvc.perform(put(ORDERS).with(customer())).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(ORDERS).with(customer())).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch(ORDERS).with(customer())).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(ORDERS + "/" + ORDER_NUMBER).with(customer())).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(ORDERS + "/" + ORDER_NUMBER).with(customer())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(checkoutService);
    }

    // ---- POST /api/v1/me/orders (Checkout) -----------------------------------------------------------------

    @Test
    void checkoutNewOrderReturns201Created() throws Exception {
        when(checkoutService.checkout(eq(8L), anyString(), eq(IDEMPOTENCY_KEY), any(CheckoutRequest.class)))
                .thenReturn(new CheckoutService.CheckoutResult(sampleOrderResponse(), false));

        mockMvc.perform(json(post(ORDERS), validCheckoutJson())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").value(ORDER_NUMBER))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.subtotal").value(599.98))
                .andExpect(jsonPath("$.pointsRedeemed").value(100))
                .andExpect(jsonPath("$.pointsDiscount").value(100.00))
                .andExpect(jsonPath("$.total").value(499.98))
                .andExpect(jsonPath("$.pointsEarned").value(50))
                .andExpect(jsonPath("$.shippingAddress.fullName").value("Jane Doe"))
                .andExpect(jsonPath("$.shippingAddress.phone").value("9876543210"))
                .andExpect(jsonPath("$.shippingAddress.postalCode").value("560001"))
                .andExpect(jsonPath("$.items[0].bookId").value(10))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(header().doesNotExist("Idempotent-Replay"));
    }

    @Test
    void checkoutReplayReturns200OkWithIdempotentReplayHeader() throws Exception {
        when(checkoutService.checkout(eq(8L), anyString(), eq(IDEMPOTENCY_KEY), any(CheckoutRequest.class)))
                .thenReturn(new CheckoutService.CheckoutResult(sampleOrderResponse(), true));

        mockMvc.perform(json(post(ORDERS), validCheckoutJson())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(customer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.orderNumber").value(ORDER_NUMBER))
                .andExpect(jsonPath("$.status").value("PLACED"));
    }

    @Test
    void checkoutWithoutIdempotencyKeyHeaderPassesNullToService() throws Exception {
        when(checkoutService.checkout(eq(8L), anyString(), isNull(), any(CheckoutRequest.class)))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key header is required."));

        mockMvc.perform(json(post(ORDERS), validCheckoutJson()).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(checkoutService).checkout(eq(8L), anyString(), isNull(), any(CheckoutRequest.class));
    }

    // ---- GET /api/v1/me/orders/{orderNumber} ---------------------------------------------------------------

    @Test
    void getOrderByNumberReturns200WithOrderDetails() throws Exception {
        when(checkoutService.getOrderByNumber(8L, ORDER_NUMBER)).thenReturn(sampleOrderResponse());

        mockMvc.perform(get(ORDERS + "/" + ORDER_NUMBER).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(ORDER_NUMBER))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.total").value(499.98))
                .andExpect(jsonPath("$.shippingAddress.city").value("Bengaluru"));
    }

    @Test
    void getOrderByNumberNotFoundReturns404() throws Exception {
        when(checkoutService.getOrderByNumber(8L, "BW-UNKNOWN"))
                .thenThrow(new ApiException(ErrorCode.NOT_FOUND, "Order not found."));

        mockMvc.perform(get(ORDERS + "/BW-UNKNOWN").with(customer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- GET /api/v1/me/orders (History) -------------------------------------------------------------------

    @Test
    void getCustomerOrderHistoryReturns200WithPagedResult() throws Exception {
        com.bookworm.common.web.PageResponse<OrderResponse> pagedResponse = new com.bookworm.common.web.PageResponse<>(
                List.of(sampleOrderResponse()), 0, 10, 1L, 1);
        when(checkoutService.getCustomerOrderHistory(eq(8L), isNull(), eq(0), eq(10), eq("createdAt"), any()))
                .thenReturn(pagedResponse);

        mockMvc.perform(get(ORDERS).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderNumber").value(ORDER_NUMBER))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ---- POST /api/v1/me/orders/{orderNumber}/cancel --------------------------------------------------------

    @Test
    void cancelOrderSucceedsAndReturns200() throws Exception {
        OrderResponse cancelledResponse = new OrderResponse(
                ORDER_NUMBER,
                OrderStatus.CANCELLED,
                new BigDecimal("599.98"),
                100L,
                new BigDecimal("100.00"),
                new BigDecimal("499.98"),
                50L,
                sampleAddress(),
                List.of(),
                List.of(),
                Instant.parse("2026-11-20T12:00:00Z"));
        when(checkoutService.cancelCustomerOrder(8L, "8", ORDER_NUMBER)).thenReturn(cancelledResponse);

        mockMvc.perform(post(ORDERS + "/" + ORDER_NUMBER + "/cancel").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(ORDER_NUMBER))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ---- Request Body Validation --------------------------------------------------------------------------

    @Test
    void checkoutRejectsMissingShippingAddress() throws Exception {
        String body = """
                {
                  "paymentToken": "tok_success",
                  "redeemPoints": 0
                }
                """;
        mockMvc.perform(json(post(ORDERS), body).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(checkoutService);
    }

    @Test
    void checkoutRejectsMissingPaymentToken() throws Exception {
        String body = """
                {
                  "shippingAddress": {
                    "fullName": "Jane Doe",
                    "phone": "9876543210",
                    "addressLine1": "123 Main St",
                    "city": "Bengaluru",
                    "state": "Karnataka",
                    "postalCode": "560001"
                  },
                  "redeemPoints": 0
                }
                """;
        mockMvc.perform(json(post(ORDERS), body).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(checkoutService);
    }

    @Test
    void checkoutRejectsBlankRequiredAddressFields() throws Exception {
        List<String> invalidAddresses = List.of(
                // Blank full name
                """
                {"shippingAddress": {"fullName":"", "phone":"9876543210", "addressLine1":"123 Main St", "city":"Bengaluru", "state":"Karnataka", "postalCode":"560001"}, "paymentToken":"tok_success"}
                """,
                // Blank address line 1
                """
                {"shippingAddress": {"fullName":"Jane", "phone":"9876543210", "addressLine1":"", "city":"Bengaluru", "state":"Karnataka", "postalCode":"560001"}, "paymentToken":"tok_success"}
                """,
                // Blank city
                """
                {"shippingAddress": {"fullName":"Jane", "phone":"9876543210", "addressLine1":"123 Main St", "city":"", "state":"Karnataka", "postalCode":"560001"}, "paymentToken":"tok_success"}
                """,
                // Blank state
                """
                {"shippingAddress": {"fullName":"Jane", "phone":"9876543210", "addressLine1":"123 Main St", "city":"Bengaluru", "state":"", "postalCode":"560001"}, "paymentToken":"tok_success"}
                """);

        for (String body : invalidAddresses) {
            mockMvc.perform(json(post(ORDERS), body).with(customer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(checkoutService);
    }

    @Test
    void checkoutRejectsInvalidPhonePatterns() throws Exception {
        List<String> badPhones = List.of("123", "987654321", "98765432100", "abcdefghij", "+919876543210", "");
        for (String phone : badPhones) {
            String body = """
                    {
                      "shippingAddress": {
                        "fullName": "Jane Doe",
                        "phone": "%s",
                        "addressLine1": "123 Main St",
                        "city": "Bengaluru",
                        "state": "Karnataka",
                        "postalCode": "560001"
                      },
                      "paymentToken": "tok_success"
                    }
                    """.formatted(phone);

            mockMvc.perform(json(post(ORDERS), body).with(customer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(checkoutService);
    }

    @Test
    void checkoutRejectsInvalidPostalCodePatterns() throws Exception {
        List<String> badPinCodes = List.of("123", "56000", "5600011", "abcdef", "560 01", "");
        for (String pin : badPinCodes) {
            String body = """
                    {
                      "shippingAddress": {
                        "fullName": "Jane Doe",
                        "phone": "9876543210",
                        "addressLine1": "123 Main St",
                        "city": "Bengaluru",
                        "state": "Karnataka",
                        "postalCode": "%s"
                      },
                      "paymentToken": "tok_success"
                    }
                    """.formatted(pin);

            mockMvc.perform(json(post(ORDERS), body).with(customer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(checkoutService);
    }

    @Test
    void checkoutRejectsNegativeRedeemPoints() throws Exception {
        String body = """
                {
                  "shippingAddress": {
                    "fullName": "Jane Doe",
                    "phone": "9876543210",
                    "addressLine1": "123 Main St",
                    "city": "Bengaluru",
                    "state": "Karnataka",
                    "postalCode": "560001"
                  },
                  "paymentToken": "tok_success",
                  "redeemPoints": -5
                }
                """;
        mockMvc.perform(json(post(ORDERS), body).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(checkoutService);
    }

    @Test
    void checkoutRejectsCoercedRedeemPoints() throws Exception {
        List<String> malformedBodies = List.of(
                // String coercion
                """
                {"shippingAddress":{"fullName":"Jane","phone":"9876543210","addressLine1":"123","city":"Blr","state":"KA","postalCode":"560001"},"paymentToken":"tok_success","redeemPoints":"100"}
                """,
                // Floating point
                """
                {"shippingAddress":{"fullName":"Jane","phone":"9876543210","addressLine1":"123","city":"Blr","state":"KA","postalCode":"560001"},"paymentToken":"tok_success","redeemPoints":10.5}
                """,
                // Boolean
                """
                {"shippingAddress":{"fullName":"Jane","phone":"9876543210","addressLine1":"123","city":"Blr","state":"KA","postalCode":"560001"},"paymentToken":"tok_success","redeemPoints":true}
                """);

        for (String body : malformedBodies) {
            mockMvc.perform(json(post(ORDERS), body).with(customer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        verifyNoInteractions(checkoutService);
    }

    @Test
    void checkoutRejectsUnknownFieldsViaStrictRequest() throws Exception {
        // Unknown field on root
        String unknownRoot = """
                {
                  "shippingAddress": {
                    "fullName": "Jane Doe",
                    "phone": "9876543210",
                    "addressLine1": "123 Main St",
                    "city": "Bengaluru",
                    "state": "Karnataka",
                    "postalCode": "560001"
                  },
                  "paymentToken": "tok_success",
                  "totalPrice": 499.98
                }
                """;
        mockMvc.perform(json(post(ORDERS), unknownRoot).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        // Unknown field on address
        String unknownAddress = """
                {
                  "shippingAddress": {
                    "fullName": "Jane Doe",
                    "phone": "9876543210",
                    "addressLine1": "123 Main St",
                    "city": "Bengaluru",
                    "state": "Karnataka",
                    "postalCode": "560001",
                    "landmark": "Near Park"
                  },
                  "paymentToken": "tok_success"
                }
                """;
        mockMvc.perform(json(post(ORDERS), unknownAddress).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(checkoutService);
    }

    // ---- Service Error Code Mappings ------------------------------------------------------------------------

    @Test
    void serviceErrorsMapToStandardCodesAndStatuses() throws Exception {
        // CART_EMPTY -> 422
        when(checkoutService.checkout(eq(8L), anyString(), eq(IDEMPOTENCY_KEY), any(CheckoutRequest.class)))
                .thenThrow(new ApiException(ErrorCode.CART_EMPTY, "Cart is empty."));
        mockMvc.perform(json(post(ORDERS), validCheckoutJson()).header("Idempotency-Key", IDEMPOTENCY_KEY).with(customer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));

        // INSUFFICIENT_STOCK -> 409
        when(checkoutService.checkout(eq(8L), anyString(), eq(IDEMPOTENCY_KEY), any(CheckoutRequest.class)))
                .thenThrow(new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock."));
        mockMvc.perform(json(post(ORDERS), validCheckoutJson()).header("Idempotency-Key", IDEMPOTENCY_KEY).with(customer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        // BOOK_UNAVAILABLE -> 409
        when(checkoutService.checkout(eq(8L), anyString(), eq(IDEMPOTENCY_KEY), any(CheckoutRequest.class)))
                .thenThrow(new ApiException(ErrorCode.BOOK_UNAVAILABLE, "Book is unavailable."));
        mockMvc.perform(json(post(ORDERS), validCheckoutJson()).header("Idempotency-Key", IDEMPOTENCY_KEY).with(customer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOK_UNAVAILABLE"));

        // POINTS_LIMIT_EXCEEDED -> 422
        when(checkoutService.checkout(eq(8L), anyString(), eq(IDEMPOTENCY_KEY), any(CheckoutRequest.class)))
                .thenThrow(new ApiException(ErrorCode.POINTS_LIMIT_EXCEEDED, "Points exceed limit."));
        mockMvc.perform(json(post(ORDERS), validCheckoutJson()).header("Idempotency-Key", IDEMPOTENCY_KEY).with(customer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("POINTS_LIMIT_EXCEEDED"));

        // PAYMENT_DECLINED -> 402
        when(checkoutService.checkout(eq(8L), anyString(), eq(IDEMPOTENCY_KEY), any(CheckoutRequest.class)))
                .thenThrow(new ApiException(ErrorCode.PAYMENT_DECLINED, "Payment declined."));
        mockMvc.perform(json(post(ORDERS), validCheckoutJson()).header("Idempotency-Key", IDEMPOTENCY_KEY).with(customer()))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));

        // PAYMENT_UNAVAILABLE -> 502
        when(checkoutService.checkout(eq(8L), anyString(), eq(IDEMPOTENCY_KEY), any(CheckoutRequest.class)))
                .thenThrow(new ApiException(ErrorCode.PAYMENT_UNAVAILABLE, "Payment gateway unavailable."));
        mockMvc.perform(json(post(ORDERS), validCheckoutJson()).header("Idempotency-Key", IDEMPOTENCY_KEY).with(customer()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_UNAVAILABLE"));

        // IDEMPOTENCY_KEY_REUSED -> 422
        when(checkoutService.checkout(eq(8L), anyString(), eq(IDEMPOTENCY_KEY), any(CheckoutRequest.class)))
                .thenThrow(new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused."));
        mockMvc.perform(json(post(ORDERS), validCheckoutJson()).header("Idempotency-Key", IDEMPOTENCY_KEY).with(customer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
}
