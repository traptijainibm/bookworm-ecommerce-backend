package com.bookworm.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@WebMvcTest(AdminOrderController.class)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class, StrictJsonConfig.class})
@WebSliceSecurity
class AdminOrderControllerTest {

    private static final String ADMIN_ORDERS = "/api/v1/admin/orders";
    private static final String ORDER_NUMBER = "BW-2026-000001";

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
        return new ShippingAddressDto("Jane Doe", "9876543210", "123 Main St", "Apt 4B", "Bengaluru",
                "Karnataka", "560001", "India");
    }

    private static OrderResponse sampleOrderResponse() {
        return new OrderResponse(
                ORDER_NUMBER,
                OrderStatus.PLACED,
                new BigDecimal("599.98"),
                100L,
                new BigDecimal("100.00"),
                new BigDecimal("499.98"),
                50L,
                sampleAddress(),
                List.of(),
                List.of(),
                Instant.parse("2026-11-20T12:00:00Z"));
    }

    @Test
    void endpointsRejectAnonymousWith401() throws Exception {
        mockMvc.perform(get(ADMIN_ORDERS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(ADMIN_ORDERS + "/" + ORDER_NUMBER))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(json(patch(ADMIN_ORDERS + "/" + ORDER_NUMBER + "/status"), "{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(checkoutService);
    }

    @Test
    void endpointsRejectCustomerWith403() throws Exception {
        mockMvc.perform(get(ADMIN_ORDERS).with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(ADMIN_ORDERS + "/" + ORDER_NUMBER).with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(patch(ADMIN_ORDERS + "/" + ORDER_NUMBER + "/status"), "{}").with(customer()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(checkoutService);
    }

    @Test
    void getAdminOrdersReturnsPagedOrdersForAdmin() throws Exception {
        PageResponse<OrderResponse> paged = new PageResponse<>(List.of(sampleOrderResponse()), 0, 20, 1L, 1);
        when(checkoutService.getAdminOrders(isNull(), eq(0), eq(20), eq("createdAt"), any()))
                .thenReturn(paged);

        mockMvc.perform(get(ADMIN_ORDERS).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderNumber").value(ORDER_NUMBER))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getAdminOrderByNumberReturnsDetails() throws Exception {
        when(checkoutService.getAdminOrder(ORDER_NUMBER)).thenReturn(sampleOrderResponse());

        mockMvc.perform(get(ADMIN_ORDERS + "/" + ORDER_NUMBER).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(ORDER_NUMBER))
                .andExpect(jsonPath("$.status").value("PLACED"));
    }

    @Test
    void updateAdminOrderStatusReturnsUpdatedOrder() throws Exception {
        OrderResponse updated = new OrderResponse(
                ORDER_NUMBER,
                OrderStatus.PROCESSING,
                new BigDecimal("599.98"),
                100L,
                new BigDecimal("100.00"),
                new BigDecimal("499.98"),
                50L,
                sampleAddress(),
                List.of(),
                List.of(),
                Instant.parse("2026-11-20T12:00:00Z"));
        when(checkoutService.updateAdminOrderStatus(eq("7"), eq(ORDER_NUMBER), any(OrderStatusUpdateRequest.class)))
                .thenReturn(updated);

        String body = """
                {
                  "status": "PROCESSING",
                  "reason": "Approved"
                }
                """;

        mockMvc.perform(json(patch(ADMIN_ORDERS + "/" + ORDER_NUMBER + "/status"), body).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }
}
