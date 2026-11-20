package com.bookworm.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookworm.common.web.StrictJsonConfig;
import com.bookworm.common.web.TraceIdFilter;
import com.bookworm.testsupport.WebSliceSecurity;
import com.bookworm.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lock-failure mapping is deliberately narrow: only {@link PessimisticLockingFailureException} and its
 * subtypes become 409 CONCURRENT_UPDATE; every other data-access failure keeps its own (unchanged) handling.
 * Slice only: the probe controller raises each exception through the real {@link GlobalExceptionHandler}.
 * This test does not establish what Hibernate or PostgreSQL actually throw; that is spike M2b.
 */
@WebMvcTest(controllers = PessimisticLockHandlerTest.ProbeController.class)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class, StrictJsonConfig.class,
        PessimisticLockHandlerTest.ProbeController.class})
@WebSliceSecurity
class PessimisticLockHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private org.springframework.test.web.servlet.request.RequestPostProcessor user() {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("8"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test
    void everyPessimisticLockFailureIsA409ConcurrentUpdateWithAGenericMessage() throws Exception {
        for (String path : new String[] {"/probe-lock/plain", "/probe-lock/cannot-acquire", "/probe-lock/deadlock",
                "/probe-lock/cannot-serialize"}) {
            mockMvc.perform(get(path).with(user()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.detail").value("The resource is being modified by another request. Please retry."));
        }
    }

    @Test
    void theInternalExceptionMessageIsNeverReturned() throws Exception {
        mockMvc.perform(get("/probe-lock/secret-detail").with(user()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("zq-internal-sql"))));
    }

    @Test
    void otherDataAccessFailuresAreNotMappedToConcurrentUpdate() throws Exception {
        mockMvc.perform(get("/probe-lock/timeout").with(user()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        mockMvc.perform(get("/probe-lock/resource-failure").with(user()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        mockMvc.perform(get("/probe-lock/generic-concurrency").with(user()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void anIntegrityViolationKeepsItsOwnMapping() throws Exception {
        mockMvc.perform(get("/probe-lock/integrity").with(user()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe-lock/plain")
        String plain() {
            throw new PessimisticLockingFailureException("plain");
        }

        @GetMapping("/probe-lock/cannot-acquire")
        String cannotAcquire() {
            throw new CannotAcquireLockException("cannot acquire");
        }

        @GetMapping("/probe-lock/deadlock")
        String deadlock() {
            throw new DeadlockLoserDataAccessException("deadlock", null);
        }

        @GetMapping("/probe-lock/cannot-serialize")
        String cannotSerialize() {
            throw new CannotSerializeTransactionException("cannot serialize");
        }

        @GetMapping("/probe-lock/secret-detail")
        String secretDetail() {
            throw new CannotAcquireLockException("zq-internal-sql select * from carts for update");
        }

        @GetMapping("/probe-lock/timeout")
        String timeout() {
            throw new QueryTimeoutException("timeout");
        }

        @GetMapping("/probe-lock/resource-failure")
        String resourceFailure() {
            throw new DataAccessResourceFailureException("down");
        }

        @GetMapping("/probe-lock/generic-concurrency")
        String genericConcurrency() {
            throw new ConcurrencyFailureException("generic");
        }

        @GetMapping("/probe-lock/integrity")
        String integrity() {
            throw new DataIntegrityViolationException("integrity");
        }
    }
}
