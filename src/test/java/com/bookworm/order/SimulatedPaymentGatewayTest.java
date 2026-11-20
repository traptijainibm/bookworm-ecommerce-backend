package com.bookworm.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    @Test
    void successTokenApprovesPayment() {
        PaymentResult result = gateway.charge("tok_success", new BigDecimal("100.00"), "ORD-123");
        assertThat(result.successful()).isTrue();
        assertThat(result.reference()).startsWith("sim_gw_");
    }

    @Test
    void declinedTokenThrowsPaymentDeclined402() {
        assertThatThrownBy(() -> gateway.charge("tok_declined", new BigDecimal("100.00"), "ORD-123"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_DECLINED));
    }

    @Test
    void errorTokenThrowsPaymentUnavailable502() {
        assertThatThrownBy(() -> gateway.charge("tok_error", new BigDecimal("100.00"), "ORD-123"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_UNAVAILABLE));
    }

    @Test
    void unknownOrEmptyTokenThrowsMalformedRequest400() {
        assertThatThrownBy(() -> gateway.charge(null, new BigDecimal("100.00"), "ORD-123"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.MALFORMED_REQUEST));

        assertThatThrownBy(() -> gateway.charge("tok_invalid", new BigDecimal("100.00"), "ORD-123"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.MALFORMED_REQUEST));
    }
}
