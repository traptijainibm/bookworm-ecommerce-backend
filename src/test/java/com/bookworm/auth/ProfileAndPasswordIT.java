package com.bookworm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ProfileAndPasswordIT extends AbstractAuthIT {

    @Test
    void meReturnsTheTokenOwnersProfileWithoutInternalFields() throws Exception {
        String email = uniqueEmail();
        String token = registerAndLogin(email);

        mockMvc.perform(me(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.failedLoginAttempts").doesNotExist())
                .andExpect(jsonPath("$.lockedUntil").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.enabled").doesNotExist());
    }

    @Test
    void theUserIsAlwaysDerivedFromTheTokenNeverFromRequestParameters() throws Exception {
        String alice = uniqueEmail();
        String bob = uniqueEmail();
        String aliceToken = registerAndLogin(alice);
        registerAndLogin(bob);

        // Alice tries to address Bob by id, email and header: every attempt still returns Alice.
        mockMvc.perform(bearer(get("/api/v1/me").param("userId", String.valueOf(userId(bob)))
                        .param("id", String.valueOf(userId(bob))).param("email", bob)
                        .header("X-User-Id", String.valueOf(userId(bob))), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(alice));
    }

    @Test
    void updatingTheProfileChangesOnlyNamesAndPhoneAndIgnoresPrivilegedFields() throws Exception {
        String email = uniqueEmail();
        String token = registerAndLogin(email);

        mockMvc.perform(bearer(put("/api/v1/me").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"firstName\":\" Grace \",\"lastName\":\"Hopper\",\"phone\":\"9876543210\","
                                + "\"email\":\"taken@example.test\",\"role\":\"ADMIN\",\"enabled\":false}"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Grace"))
                .andExpect(jsonPath("$.phone").value("9876543210"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, email))
                .isEqualTo("CUSTOMER");
        assertThat(jdbc.queryForObject("select enabled from users where email = ?", Boolean.class, email)).isTrue();
    }

    @Test
    void profileUpdateValidatesItsInput() throws Exception {
        String token = registerAndLogin(uniqueEmail());

        mockMvc.perform(bearer(put("/api/v1/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"\",\"lastName\":\"X\",\"phone\":\"123\"}"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void changingThePasswordWorksAndTheOldPasswordStopsWorking() throws Exception {
        String email = uniqueEmail();
        String token = registerAndLogin(email);

        mockMvc.perform(bearer(jsonPost("/api/v1/me/password", json("currentPassword", PASSWORD,
                        "newPassword", "a brand new password")), token))
                .andExpect(status().isNoContent());

        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(email, "a brand new password").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void existingTokensStayValidAfterAPasswordChange_documentedLimitation() throws Exception {
        // Tokens are not revoked on password change; they live until they expire (60 minutes). See docs/DESIGN.md.
        String email = uniqueEmail();
        String oldToken = registerAndLogin(email);

        mockMvc.perform(bearer(jsonPost("/api/v1/me/password", json("currentPassword", PASSWORD,
                "newPassword", "a brand new password")), oldToken)).andExpect(status().isNoContent());

        mockMvc.perform(me(oldToken)).andExpect(status().isOk());
    }

    @Test
    void passwordChangeRejectsWeakReusedAndWrongCurrentPasswords() throws Exception {
        String email = uniqueEmail();
        String token = registerAndLogin(email);

        mockMvc.perform(bearer(jsonPost("/api/v1/me/password", json("currentPassword", PASSWORD,
                        "newPassword", "short")), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        mockMvc.perform(bearer(jsonPost("/api/v1/me/password", json("currentPassword", PASSWORD,
                        "newPassword", PASSWORD)), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("must differ from the current password"));
        mockMvc.perform(bearer(jsonPost("/api/v1/me/password", json("currentPassword", "not the password",
                        "newPassword", "a brand new password")), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"));

        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void passwordChangeRequiresAuthentication() throws Exception {
        mockMvc.perform(jsonPost("/api/v1/me/password", json("currentPassword", PASSWORD,
                        "newPassword", "a brand new password")))
                .andExpect(status().isUnauthorized());
    }
}
