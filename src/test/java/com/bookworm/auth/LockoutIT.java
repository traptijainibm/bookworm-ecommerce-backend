package com.bookworm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Lockout: 5 consecutive failures lock the account for 15 minutes. These tests also prove the two properties
 * the design depends on: the failure is COMMITTED even though the request ends in an error ({@code REQUIRES_NEW}),
 * and the counter cannot lose increments under parallel guessing (single atomic UPDATE).
 */
class LockoutIT extends AbstractAuthIT {

    private void failTimes(String email, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(login(email, "wrong password " + i).getResponse().getStatus()).isEqualTo(401);
        }
    }

    @Test
    void failuresAreCommittedEvenThoughEachRequestEndsInAnError() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        failTimes(email, 3);

        assertThat(failedAttempts(email)).isEqualTo(3);
    }

    @Test
    void theFifthFailureLocksTheAccountAndEvenTheCorrectPasswordIsThenRefused() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        failTimes(email, 5);

        assertThat(jdbc.queryForObject("select locked_until is not null from users where email = ?",
                Boolean.class, email)).isTrue();
        MvcResult locked = login(email, PASSWORD);
        assertThat(locked.getResponse().getStatus()).isEqualTo(423);
        assertThat(field(locked, "$.code")).isEqualTo("ACCOUNT_LOCKED");
        assertThat(locked.getResponse().getContentAsString()).doesNotContain(PASSWORD);
    }

    @Test
    void theCounterDoesNotMoveWhileTheAccountIsLocked() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        failTimes(email, 5);
        int before = failedAttempts(email);

        for (int i = 0; i < 4; i++) {
            assertThat(login(email, "still wrong").getResponse().getStatus()).isEqualTo(423);
        }

        assertThat(failedAttempts(email)).isEqualTo(before);
    }

    @Test
    void theLockLastsFifteenMinutesThenLoginWorksAgainAndTheCounterResets() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        failTimes(email, 5);
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(423);

        clock.advance(Duration.ofMinutes(14).plusSeconds(30));
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(423);

        clock.advance(Duration.ofMinutes(1));
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(failedAttempts(email)).isZero();
        assertThat(jdbc.queryForObject("select locked_until is null from users where email = ?",
                Boolean.class, email)).isTrue();
    }

    @Test
    void afterTheLockExpiresAWrongPasswordStartsANewCountInsteadOfRelockingImmediately() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        failTimes(email, 5);
        clock.advance(Duration.ofMinutes(16));

        assertThat(login(email, "wrong after expiry").getResponse().getStatus()).isEqualTo(401);

        assertThat(failedAttempts(email)).isEqualTo(1);
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void lockoutIsPerAccountNotGlobal() throws Exception {
        String victim = uniqueEmail();
        String other = uniqueEmail();
        register(victim, PASSWORD);
        register(other, PASSWORD);

        failTimes(victim, 5);

        assertThat(login(victim, PASSWORD).getResponse().getStatus()).isEqualTo(423);
        assertThat(login(other, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anAttackerCanLockAVictimOutWithoutKnowingThePassword() throws Exception {
        // Documented limitation (no per-IP rate limit): anyone who knows an email can lock that account.
        String victim = uniqueEmail();
        register(victim, PASSWORD);

        failTimes(victim, 5);

        MvcResult legitimateOwner = login(victim, PASSWORD);
        assertThat(legitimateOwner.getResponse().getStatus()).isEqualTo(423);
    }

    @Test
    void parallelWrongGuessesNeverLoseIncrementsAndTheAccountEndsLocked() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                attempts.add(() -> login(email, "parallel wrong guess " + n).getResponse().getStatus());
            }
            for (Future<Integer> result : pool.invokeAll(attempts)) {
                assertThat(result.get(60, TimeUnit.SECONDS)).isIn(401, 423);
            }
        } finally {
            pool.shutdownNow();
        }

        // 8 parallel failures against a limit of 5: exactly 5 can be counted, then the lock stops further counting.
        assertThat(failedAttempts(email)).isEqualTo(5);
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(423);
    }

    @Test
    void aWrongCurrentPasswordOnPasswordChangeCountsTowardsTheSameLockout() throws Exception {
        String email = uniqueEmail();
        String token = registerAndLogin(email);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(bearer(jsonPost("/api/v1/me/password", json("currentPassword", "guess number " + i,
                            "newPassword", "a brand new password")), token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("currentPassword"));
        }

        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(423);
        mockMvc.perform(bearer(jsonPost("/api/v1/me/password", json("currentPassword", PASSWORD,
                        "newPassword", "a brand new password")), token))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }
}
