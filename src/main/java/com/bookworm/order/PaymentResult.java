package com.bookworm.order;

public record PaymentResult(boolean successful, String reference) {

    public static PaymentResult success(String reference) {
        return new PaymentResult(true, reference);
    }
}
