package com.bookworm.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookworm.common.web.StrictIntegerDeserializer;
import com.bookworm.common.web.StrictJsonConfig;
import com.bookworm.common.web.StrictLongDeserializer;
import com.bookworm.common.web.StrictRequest;
import com.bookworm.common.web.TraceIdFilter;
import com.bookworm.testsupport.WebSliceSecurity;
import com.bookworm.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Phase 4 (M3) error codes and strict JSON, at the HTTP level. A probe controller raises each new code through
 * the real {@link GlobalExceptionHandler} and reads a body that uses the strict deserializers. Slice only: no
 * database, no Flyway, no application context beyond the web layer.
 */
@WebMvcTest(controllers = Phase4ErrorHandlingTest.ProbeController.class)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class, StrictJsonConfig.class,
        Phase4ErrorHandlingTest.ProbeController.class})
@WebSliceSecurity
@WithMockUser
class Phase4ErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // ---- the five new codes: status, stable code, traceId, generic detail ----

    @Test
    void insufficientStockIsA409WithItsCodeAndNoStockCount() throws Exception {
        mockMvc.perform(get("/probe4/insufficient-stock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.detail").value("Not enough stock is available for the requested quantity."));
    }

    @Test
    void bookUnavailableIsA409() throws Exception {
        mockMvc.perform(get("/probe4/unavailable"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOK_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void quantityLimitExceededIsA422() throws Exception {
        mockMvc.perform(get("/probe4/quantity-limit"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void cartFullIsA422() throws Exception {
        mockMvc.perform(get("/probe4/cart-full"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_FULL"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void wishlistFullIsA422() throws Exception {
        mockMvc.perform(get("/probe4/wishlist-full"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WISHLIST_FULL"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void theNewCodesHaveTheApprovedStatuses() {
        assertThat(ErrorCode.INSUFFICIENT_STOCK.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.BOOK_UNAVAILABLE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.QUANTITY_LIMIT_EXCEEDED.status().value()).isEqualTo(422);
        assertThat(ErrorCode.CART_FULL.status().value()).isEqualTo(422);
        assertThat(ErrorCode.WISHLIST_FULL.status().value()).isEqualTo(422);
    }

    @Test
    void existingCodesKeepTheirStatuses() {
        assertThat(ErrorCode.VALIDATION_FAILED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.DUPLICATE_RESOURCE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CONCURRENT_UPDATE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.INTERNAL_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ---- strict JSON over HTTP ----

    @Test
    void validBodyIsAccepted() throws Exception {
        mockMvc.perform(json("{\"bookId\":1,\"quantity\":2}")).andExpect(status().isOk());
    }

    @Test
    void wrongTokenTypesAreMalformedRequestsAndNeverEchoTheValue() throws Exception {
        List<String> bodies = List.of(
                "{\"bookId\":1,\"quantity\":\"2\"}",
                "{\"bookId\":1,\"quantity\":\"zq-echo-7731\"}",
                "{\"bookId\":1,\"quantity\":2.7}",
                "{\"bookId\":1,\"quantity\":2.0}",
                "{\"bookId\":1,\"quantity\":1e1}",
                "{\"bookId\":1,\"quantity\":\"\"}",
                "{\"bookId\":1,\"quantity\":true}",
                "{\"bookId\":1,\"quantity\":[2]}",
                "{\"bookId\":1,\"quantity\":{}}",
                "{\"bookId\":\"7\",\"quantity\":2}",
                "{\"bookId\":7.9,\"quantity\":2}",
                "{\"bookId\":1,\"quantity\":3000000000}",
                "{\"bookId\":99999999999999999999,\"quantity\":2}");
        for (String body : bodies) {
            mockMvc.perform(json(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.detail").value("Failed to read request"))
                    .andExpect(jsonPath("$.detail", not(containsString("zq-echo"))));
        }
    }

    @Test
    void nullMissingAndOutOfRangeValuesAreValidationFailures() throws Exception {
        List<String> bodies = List.of(
                "{\"bookId\":1,\"quantity\":null}",
                "{\"bookId\":null,\"quantity\":2}",
                "{\"bookId\":1}",
                "{\"quantity\":2}",
                "{}",
                "{\"bookId\":1,\"quantity\":0}",
                "{\"bookId\":1,\"quantity\":11}",
                "{\"bookId\":1,\"quantity\":-1}",
                "{\"bookId\":0,\"quantity\":2}");
        for (String body : bodies) {
            mockMvc.perform(json(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void unknownFieldsAreMalformedRequests() throws Exception {
        for (String extra : List.of("\"price\":1", "\"userId\":9", "\"zqUnknownField\":1")) {
            mockMvc.perform(json("{\"bookId\":1,\"quantity\":2," + extra + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.detail", not(containsString("zqUnknownField"))));
        }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(String body) {
        return post("/probe4/strict").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    public record StrictBody(
            @NotNull @Min(1) @Max(10) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer quantity,
            @NotNull @Min(1) @JsonDeserialize(using = StrictLongDeserializer.class) Long bookId)
            implements StrictRequest {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe4/insufficient-stock")
        String insufficientStock() {
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                    "Not enough stock is available for the requested quantity.");
        }

        @GetMapping("/probe4/unavailable")
        String unavailable() {
            throw new ApiException(ErrorCode.BOOK_UNAVAILABLE, "This book is no longer available.");
        }

        @GetMapping("/probe4/quantity-limit")
        String quantityLimit() {
            throw new ApiException(ErrorCode.QUANTITY_LIMIT_EXCEEDED, "A line cannot hold more than 10 copies.");
        }

        @GetMapping("/probe4/cart-full")
        String cartFull() {
            throw new ApiException(ErrorCode.CART_FULL, "The cart cannot hold more than 50 different books.");
        }

        @GetMapping("/probe4/wishlist-full")
        String wishlistFull() {
            throw new ApiException(ErrorCode.WISHLIST_FULL, "The wishlist cannot hold more than 200 books.");
        }

        @PostMapping("/probe4/strict")
        String strict(@Valid @RequestBody StrictBody body) {
            return "ok";
        }
    }
}
