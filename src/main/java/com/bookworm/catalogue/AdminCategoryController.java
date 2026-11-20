package com.bookworm.catalogue;

import com.bookworm.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin category management. There is no delete. The slug is generated on create and never changes. */
@RestController
@RequestMapping("/api/v1/admin/categories")
@Tag(name = "Admin catalogue")
public class AdminCategoryController {

    private static final String BEARER = "bearerAuth";

    private final AdminCategoryService service;

    public AdminCategoryController(AdminCategoryService service) {
        this.service = service;
    }

    @Operation(summary = "Create a category (slug generated from the name)",
            security = @SecurityRequirement(name = BEARER))
    @PostMapping
    public ResponseEntity<AdminCategoryResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody CategoryRequest request) {
        AdminCategoryResponse created = service.create(CurrentUser.id(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/admin/categories/" + created.id())).body(created);
    }

    @Operation(summary = "List categories with book counts", security = @SecurityRequirement(name = BEARER))
    @GetMapping
    public List<AdminCategoryResponse> list() {
        return service.list();
    }

    @Operation(summary = "Get a category", security = @SecurityRequirement(name = BEARER))
    @GetMapping("/{id}")
    public AdminCategoryResponse get(@PathVariable @Min(1) Long id) {
        return service.get(id);
    }

    @Operation(summary = "Rename a category (the slug does not change)",
            security = @SecurityRequirement(name = BEARER))
    @PutMapping("/{id}")
    public AdminCategoryResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) Long id,
                                        @Valid @RequestBody CategoryRequest request) {
        return service.rename(CurrentUser.id(jwt), id, request);
    }
}
