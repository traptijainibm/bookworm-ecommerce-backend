package com.bookworm.order;

import java.math.BigDecimal;

public interface PaymentGateway {

    PaymentResult charge(String paymentToken, BigDecimal amount, String orderNumber);
}
