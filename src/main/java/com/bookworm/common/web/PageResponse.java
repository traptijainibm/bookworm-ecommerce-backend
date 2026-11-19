package com.bookworm.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable JSON shape for paginated results (instead of serialising Spring's {@code Page} directly). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
