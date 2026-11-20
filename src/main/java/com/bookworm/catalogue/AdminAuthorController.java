package com.bookworm.catalogue;

import com.bookworm.common.security.CurrentUser;
import com.bookworm.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin author management. There is no delete. */
@RestController
@RequestMapping("/api/v1/admin/authors")
@Tag(name = "Admin catalogue")
public class AdminAuthorController {

    private static final String BEARER = "bearerAuth";

    private final AdminAuthorService service;

    public AdminAuthorController(AdminAuthorService service) {
        this.service = service;
    }

    @Operation(summary = "Create an author", security = @SecurityRequirement(name = BEARER))
    @PostMapping
    public ResponseEntity<AdminAuthorResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody AuthorRequest request) {
        AdminAuthorResponse created = service.create(CurrentUser.id(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/admin/authors/" + created.id())).body(created);
    }

    @Operation(summary = "Search and list authors with book counts", security = @SecurityRequirement(name = BEARER))
    @GetMapping
    public PageResponse<AdminAuthorResponse> search(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "(name|id)(,(asc|desc))?",
                    message = "must be name or id, optionally followed by ,asc or ,desc") String sort) {
        String[] parts = sort.split(",");
        Sort.Direction direction = parts.length > 1 ? Sort.Direction.fromString(parts[1]) : Sort.Direction.ASC;
        return service.search(q, page, size, parts[0], direction);
    }

    @Operation(summary = "Get an author", security = @SecurityRequirement(name = BEARER))
    @GetMapping("/{id}")
    public AdminAuthorResponse get(@PathVariable @Min(1) Long id) {
        return service.get(id);
    }

    @Operation(summary = "Rename an author", security = @SecurityRequirement(name = BEARER))
    @PutMapping("/{id}")
    public AdminAuthorResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) Long id,
                                      @Valid @RequestBody AuthorRequest request) {
        return service.rename(CurrentUser.id(jwt), id, request);
    }
}
