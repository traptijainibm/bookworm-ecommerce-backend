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
 * Replace a book's descriptive fields. {@code version} must be the version last read; a stale value is a 409.
 * Stock and the active flag cannot be changed here.
 */
@Schema(name = "BookUpdateRequest")
public record BookUpdateRequest(
        @NotNull @Min(0) @Schema(description = "The book's current version, as last read") Long version,
        @NotNull @Size(max = Isbn.MAX_RAW_LENGTH) String isbn,
        @NotBlank @Size(max = 300) String title,
        @Size(max = 10000) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @Size(max = 200) String publisher,
        LocalDate publishedDate,
        @Size(max = 500) String coverUrl,
        @NotNull @Min(1) Long categoryId,
        @NotEmpty @Size(max = 10) List<@NotNull @Min(1) Long> authorIds) implements BookFields, StrictRequest {
}
