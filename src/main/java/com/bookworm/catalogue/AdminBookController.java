package com.bookworm.catalogue;

import com.bookworm.common.security.CurrentUser;
import com.bookworm.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin book management. {@code /api/v1/admin/**} requires the ADMIN role (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/books")
@Tag(name = "Admin catalogue", description = "Manage books, categories and authors (ADMIN only)")
public class AdminBookController {

    private static final String BEARER = "bearerAuth";

    private final AdminBookService service;

    public AdminBookController(AdminBookService service) {
        this.service = service;
    }

    @Operation(summary = "Create a book (inactive, stock 0)", security = @SecurityRequirement(name = BEARER))
    @PostMapping
    public ResponseEntity<AdminBookResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody BookCreateRequest request) {
        AdminBookResponse created = service.create(CurrentUser.id(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/admin/books/" + created.id())).body(created);
    }

    @Operation(summary = "Search and list books (active and inactive)",
            security = @SecurityRequirement(name = BEARER),
            description = "q matches title, author name, or an exact ISBN. Wildcard characters match literally.")
    @GetMapping
    public PageResponse<AdminBookSummaryResponse> search(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Min(1) Long categoryId,
            @Parameter(description = "true, false, or omit for both")
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "id | title | isbn | price | publishedDate | updatedAt, optionally ,asc or ,desc")
            @RequestParam(defaultValue = "id") @Pattern(
                    regexp = "(id|title|isbn|price|publishedDate|updatedAt)(,(asc|desc))?",
                    message = "must be one of id, title, isbn, price, publishedDate, updatedAt, "
                            + "optionally followed by ,asc or ,desc") String sort) {
        String[] parts = sort.split(",");
        Sort.Direction direction = parts.length > 1 ? Sort.Direction.fromString(parts[1]) : Sort.Direction.ASC;
        return service.search(q, categoryId, active, page, size, parts[0], direction);
    }

    @Operation(summary = "Get a book (active or inactive)", security = @SecurityRequirement(name = BEARER))
    @GetMapping("/{id}")
    public AdminBookResponse get(@PathVariable @Min(1) Long id) {
        return service.get(id);
    }

    @Operation(summary = "Replace a book's details (requires the current version)",
            security = @SecurityRequirement(name = BEARER))
    @PutMapping("/{id}")
    public AdminBookResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) Long id,
                                    @Valid @RequestBody BookUpdateRequest request) {
        return service.update(CurrentUser.id(jwt), id, request);
    }

    @Operation(summary = "Activate or deactivate a book (idempotent)",
            security = @SecurityRequirement(name = BEARER))
    @PatchMapping("/{id}/active")
    public AdminBookResponse setActive(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) Long id,
                                       @Valid @RequestBody BookActiveRequest request) {
        return service.setActive(CurrentUser.id(jwt), id, request);
    }
}
