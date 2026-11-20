package com.bookworm.order;

import com.bookworm.common.web.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "ShippingAddress")
public record ShippingAddressDto(
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Pattern(regexp = "^[0-9]{10}$", message = "Phone must be a 10-digit number") String phone,
        @NotBlank @Size(max = 300) String addressLine1,
        @Size(max = 300) String addressLine2,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 100) String state,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "Postal code must be a 6-digit PIN code") String postalCode,
        @Size(max = 100) String country)
        implements StrictRequest {

    public static ShippingAddressDto from(ShippingAddressEmbeddable embeddable) {
        if (embeddable == null) {
            return null;
        }
        return new ShippingAddressDto(
                embeddable.getFullName(),
                embeddable.getPhone(),
                embeddable.getAddressLine1(),
                embeddable.getAddressLine2(),
                embeddable.getCity(),
                embeddable.getState(),
                embeddable.getPostalCode(),
                embeddable.getCountry());
    }
}
