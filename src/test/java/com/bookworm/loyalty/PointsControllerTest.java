package com.bookworm.loyalty;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookworm.common.error.GlobalExceptionHandler;
import com.bookworm.common.web.PageResponse;
import com.bookworm.common.web.StrictJsonConfig;
import com.bookworm.common.web.TraceIdFilter;
import com.bookworm.testsupport.WebSliceSecurity;
import com.bookworm.user.UserService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PointsController.class)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class, StrictJsonConfig.class})
@WebSliceSecurity
class PointsControllerTest {

    private static final String POINTS_PATH = "/api/v1/me/points";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoyaltyService loyaltyService;

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

    @Test
    void getPointsSummaryRejectsAnonymousWith401() throws Exception {
        mockMvc.perform(get(POINTS_PATH))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(loyaltyService);
    }

    @Test
    void getPointsSummaryRejectsAdminWith403() throws Exception {
        mockMvc.perform(get(POINTS_PATH).with(admin()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(loyaltyService);
    }

    @Test
    void getPointsSummaryAllowsCustomerAndReturnsData() throws Exception {
        PageResponse<PointsTransactionResponse> pagedHistory = new PageResponse<>(List.of(), 0, 10, 0, 0);
        PointsResponse response = new PointsResponse(250L, pagedHistory);

        when(loyaltyService.getPointsSummary(eq(8L), eq(0), eq(10))).thenReturn(response);

        mockMvc.perform(get(POINTS_PATH).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointsBalance").value(250))
                .andExpect(jsonPath("$.history.content").isArray());
    }
}
