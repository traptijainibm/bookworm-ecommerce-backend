package com.bookworm.catalogue;

import com.bookworm.common.web.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Create a book. New books are always created inactive with stock 0; there is no stock or active field, and
 * unknown JSON properties are rejected (400), so neither can be smuggled in.
 */
@Schema(name = "BookCreateRequest")
public record BookCreateRequest(
        @NotNull @Size(max = Isbn.MAX_RAW_LENGTH)
        @Schema(description = "ISBN-10 or ISBN-13, hyphens and spaces allowed; stored as ISBN-13") String isbn,
        @NotBlank @Size(max = 300) String title,
        @Size(max = 10000) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @Size(max = 200) String publisher,
        LocalDate publishedDate,
        @Size(max = 500) @Schema(description = "https URL without credentials") String coverUrl,
        @NotNull @Min(1) Long categoryId,
        @NotEmpty @Size(max = 10) List<@NotNull @Min(1) Long> authorIds) implements BookFields, StrictRequest {
}
