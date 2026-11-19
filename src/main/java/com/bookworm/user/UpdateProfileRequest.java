package com.bookworm.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Only these fields can be changed. There is deliberately no email, role or enabled field (no mass assignment). */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @Pattern(regexp = "[0-9]{10}", message = "must be 10 digits") String phone) {
}
