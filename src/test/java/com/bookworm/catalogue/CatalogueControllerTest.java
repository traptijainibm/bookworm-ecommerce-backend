package com.bookworm.catalogue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.bookworm.common.error.GlobalExceptionHandler;
import com.bookworm.common.web.PageResponse;
import com.bookworm.common.web.TraceIdFilter;
import com.bookworm.testsupport.WebSliceSecurity;
import com.bookworm.user.UserService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CatalogueController.class)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
@WebSliceSecurity
class CatalogueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogueService catalogueService;

    // Needed by the real security filter chain loaded through @WebSliceSecurity.
    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void searchUsesDefaultsWhenNoParametersAreGiven() throws Exception {
        when(catalogueService.search(any(), any(), eq(0), eq(20), eq("title"), eq(Sort.Direction.ASC)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void searchParsesSortDirectionAndPassesFilters() throws Exception {
        when(catalogueService.search(any(), any(), eq(1), eq(5), eq("price"), eq(Sort.Direction.DESC)))
                .thenReturn(new PageResponse<>(List.of(), 1, 5, 0, 0));

        mockMvc.perform(get("/api/v1/books")
                        .param("q", "dune").param("category", "science-fiction")
                        .param("page", "1").param("size", "5").param("sort", "price,desc"))
                .andExpect(status().isOk());

        verify(catalogueService).search("dune", "science-fiction", 1, 5, "price", Sort.Direction.DESC);
    }

    @Test
    void sizeAbove100IsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        verifyNoInteractions(catalogueService);
    }

    @Test
    void sizeBelow1AndNegativePageAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/books").param("page", "-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(catalogueService);
    }

    @Test
    void queryLongerThan100CharactersIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("q"));
        verifyNoInteractions(catalogueService);
    }

    @Test
    void sortFieldOutsideTheAllowlistIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("sort", "stockQuantity"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
        mockMvc.perform(get("/api/v1/books").param("sort", "title,sideways"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(catalogueService);
    }

    @Test
    void categoryThatIsNotASlugIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("category", "Sci Fi; DROP TABLE books"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("category"));
        verifyNoInteractions(catalogueService);
    }

    @Test
    void nonNumericOrNonPositiveBookIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/books/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get("/api/v1/books/0")).andExpect(status().isBadRequest());
        verifyNoInteractions(catalogueService);
    }

    @Test
    void relatedLimitMustBeBetween1And20() throws Exception {
        mockMvc.perform(get("/api/v1/books/1/related").param("limit", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/books/1/related").param("limit", "21")).andExpect(status().isBadRequest());
        verifyNoInteractions(catalogueService);
    }

    @Test
    void relatedDefaultsToEightBooks() throws Exception {
        when(catalogueService.getRelated(1L, 8)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/books/1/related")).andExpect(status().isOk());

        verify(catalogueService).getRelated(1L, 8);
    }

    @Test
    void catalogueEndpointsRejectEveryWriteMethod() throws Exception {
        // Authenticated (the security layer is bypassed with a ready-made token) so the 405 comes from MVC.
        for (String path : List.of("/api/v1/books", "/api/v1/books/1", "/api/v1/books/1/related", "/api/v1/categories")) {
            mockMvc.perform(post(path).with(jwt())).andExpect(status().isMethodNotAllowed());
            mockMvc.perform(put(path).with(jwt())).andExpect(status().isMethodNotAllowed());
            mockMvc.perform(patch(path).with(jwt())).andExpect(status().isMethodNotAllowed());
            mockMvc.perform(delete(path).with(jwt())).andExpect(status().isMethodNotAllowed());
        }
        verifyNoInteractions(catalogueService);
    }

    @Test
    void anonymousWritesToCatalogueAreRejectedAsUnauthenticated() throws Exception {
        for (String path : List.of("/api/v1/books", "/api/v1/books/1", "/api/v1/categories")) {
            mockMvc.perform(post(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(delete(path)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(catalogueService);
    }

    @Test
    void catalogueReadsStayPublicWithoutAToken() throws Exception {
        when(catalogueService.getCategories()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
    }
}
