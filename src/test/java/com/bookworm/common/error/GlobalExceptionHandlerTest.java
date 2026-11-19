package com.bookworm.common.error;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookworm.common.web.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class, GlobalExceptionHandlerTest.ProbeController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void invalidBodyReturnsFieldLevelValidationProblem() throws Exception {
        mockMvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void invalidRequestParameterReturnsValidationProblem() throws Exception {
        mockMvc.perform(get("/probe/param").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void malformedJsonReturnsMalformedRequestWithoutParserDetails() throws Exception {
        mockMvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void typeMismatchReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/probe/param").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void apiExceptionUsesItsErrorCodeAndStatus() throws Exception {
        mockMvc.perform(get("/probe/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Book 42 does not exist"));
    }

    @Test
    void unsupportedMethodReturnsProblemWithCode() throws Exception {
        mockMvc.perform(post("/probe/api"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void optimisticLockFailureReturnsConflict() throws Exception {
        mockMvc.perform(get("/probe/lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));
    }

    @Test
    void dataIntegrityViolationReturnsConflictWithoutLeakingSql() throws Exception {
        mockMvc.perform(get("/probe/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"))
                .andExpect(jsonPath("$.detail", not(containsString("uk_books_isbn"))));
    }

    @Test
    void unexpectedExceptionReturnsGenericErrorWithoutInternals() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().exists(TraceIdFilter.HEADER));
    }

    @RestController
    static class ProbeController {

        record Payload(@NotBlank String name) {
        }

        @PostMapping("/probe/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return payload.name();
        }

        @GetMapping("/probe/param")
        String param(@RequestParam @Min(1) int size) {
            return "ok";
        }

        @GetMapping("/probe/api")
        String api() {
            throw new ApiException(ErrorCode.NOT_FOUND, "Book 42 does not exist");
        }

        @GetMapping("/probe/lock")
        String lock() {
            throw new OptimisticLockingFailureException("row was updated or deleted by another transaction");
        }

        @GetMapping("/probe/integrity")
        String integrity() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint \"uk_books_isbn\"");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("jdbc:postgresql://secret-host/db password=hunter2");
        }
    }
}
