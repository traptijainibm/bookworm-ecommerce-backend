package com.bookworm.catalogue;

import com.bookworm.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, read-only catalogue endpoints. Phase 1 defines GET mappings only. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Catalogue", description = "Browse, search and filter books (read-only)")
public class CatalogueController {

    private final CatalogueService catalogueService;

    public CatalogueController(CatalogueService catalogueService) {
        this.catalogueService = catalogueService;
    }

    @Operation(summary = "Search and list books",
            description = "Case-insensitive substring search on title and author name. Wildcard characters "
                    + "(% _ \\) in q match literally. Only active books are returned.")
    @GetMapping("/books")
    public PageResponse<BookSummaryResponse> searchBooks(
            @Parameter(description = "Text to find in the title or an author name (max 100 characters)")
            @RequestParam(required = false) @Size(max = 100) String q,
            @Parameter(description = "Category slug, e.g. science-fiction")
            @RequestParam(required = false) @Size(max = 100)
            @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "must be a category slug") String category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "title | price | publishedDate, optionally followed by ,asc or ,desc")
            @RequestParam(defaultValue = "title") @Pattern(
                    regexp = "(title|price|publishedDate)(,(asc|desc))?",
                    message = "must be one of title, price, publishedDate, optionally followed by ,asc or ,desc")
            String sort) {
        String[] parts = sort.split(",");
        Sort.Direction direction = parts.length > 1 ? Sort.Direction.fromString(parts[1]) : Sort.Direction.ASC;
        return catalogueService.search(q, category, page, size, parts[0], direction);
    }

    @Operation(summary = "Get book details")
    @GetMapping("/books/{id}")
    public BookDetailResponse getBook(@PathVariable @Min(1) Long id) {
        return catalogueService.getBook(id);
    }

    @Operation(summary = "Related books",
            description = "Books sharing an author first, then books in the same category; ordered by title "
                    + "then id. The book itself and inactive books are excluded.")
    @GetMapping("/books/{id}/related")
    public List<BookSummaryResponse> getRelated(@PathVariable @Min(1) Long id,
                                                @RequestParam(defaultValue = "8") @Min(1) @Max(20) int limit) {
        return catalogueService.getRelated(id, limit);
    }

    @Operation(summary = "List categories")
    @GetMapping("/categories")
    public List<CategoryResponse> getCategories() {
        return catalogueService.getCategories();
    }
}
