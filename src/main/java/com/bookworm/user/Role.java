package com.bookworm.user;

/** Account roles. Registration always yields {@link #CUSTOMER}; {@link #ADMIN} comes only from the bootstrap. */
public enum Role {
    CUSTOMER,
    ADMIN
}
