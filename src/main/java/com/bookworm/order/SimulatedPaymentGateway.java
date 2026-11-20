package com.bookworm.order;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * In-process simulated payment gateway.
 * Tokens:
 * <ul>
 *   <li>{@code tok_success}: Payment succeeds</li>
 *   <li>{@code tok_declined}: 402 PAYMENT_DECLINED</li>
 *   <li>{@code tok_error}: 502 PAYMENT_UNAVAILABLE</li>
 *   <li>any other: 400 MALFORMED_REQUEST</li>
 * </ul>
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    @Override
    public PaymentResult charge(String paymentToken, BigDecimal amount, String orderNumber) {
        if (paymentToken == null || paymentToken.isBlank()) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Payment token is required.");
        }
        if ("tok_success".equals(paymentToken)) {
            return PaymentResult.success("sim_gw_" + UUID.randomUUID().toString().replace("-", ""));
        }
        if ("tok_declined".equals(paymentToken)) {
            throw new ApiException(ErrorCode.PAYMENT_DECLINED, "Payment was declined by the issuer.");
        }
        if ("tok_error".equals(paymentToken)) {
            throw new ApiException(ErrorCode.PAYMENT_UNAVAILABLE, "Payment gateway is temporarily unavailable.");
        }
        throw new ApiException(ErrorCode.MALFORMED_REQUEST,
                "Invalid payment token. Supported tokens: tok_success, tok_declined, tok_error.");
    }
}
