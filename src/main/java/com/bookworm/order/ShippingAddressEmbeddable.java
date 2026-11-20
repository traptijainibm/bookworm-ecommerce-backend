package com.bookworm.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Immutable snapshot of the shipping address stored directly in the orders table.
 */
@Embeddable
public class ShippingAddressEmbeddable {

    @Column(name = "shipping_full_name", nullable = false)
    private String fullName;

    @Column(name = "shipping_phone", nullable = false)
    private String phone;

    @Column(name = "shipping_line1", nullable = false)
    private String addressLine1;

    @Column(name = "shipping_line2")
    private String addressLine2;

    @Column(name = "shipping_city", nullable = false)
    private String city;

    @Column(name = "shipping_state", nullable = false)
    private String state;

    @Column(name = "shipping_postal_code", nullable = false)
    private String postalCode;

    @Column(name = "shipping_country", nullable = false)
    private String country;

    protected ShippingAddressEmbeddable() {
    }

    public static ShippingAddressEmbeddable of(ShippingAddressDto dto) {
        ShippingAddressEmbeddable addr = new ShippingAddressEmbeddable();
        addr.fullName = dto.fullName();
        addr.phone = dto.phone();
        addr.addressLine1 = dto.addressLine1();
        addr.addressLine2 = dto.addressLine2();
        addr.city = dto.city();
        addr.state = dto.state();
        addr.postalCode = dto.postalCode();
        addr.country = dto.country() != null ? dto.country() : "India";
        return addr;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }
}
