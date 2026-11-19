package com.bookworm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookworm.testsupport.TestJwtSecretInitializer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Token attacks and the per-request current-user check. A signed, unexpired token is NOT enough: the account
 * must still exist and be enabled, and the authority comes from the user's CURRENT database role.
 */
class JwtSecurityIT extends AbstractAuthIT {

    private ResultActions callMe(String token) throws Exception {
        return mockMvc.perform(me(token));
    }

    private void assertRejectedAsUnauthenticated(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                // The response never says why: no "expired", "signature", "alg" or class names.
                .andExpect(jsonPath("$.detail").value("Authentication is required to access this resource."));
    }

    private String hmacToken(JWSAlgorithm algorithm, byte[] key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }

    private JWTClaimsSet.Builder nimbusClaims(String subject, String role, Instant expires) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issuer("bookworm")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(expires));
        if (subject != null) {
            builder.subject(subject);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        return builder;
    }

    // ---- forged and invalid tokens ----------------------------------------------------------------

    @Test
    void noTokenMalformedTokenAndGarbageAreAllUnauthenticated() throws Exception {
        assertRejectedAsUnauthenticated(mockMvc.perform(get("/api/v1/me")));
        assertRejectedAsUnauthenticated(callMe("not-a-jwt"));
        assertRejectedAsUnauthenticated(callMe("a.b.c"));
        assertRejectedAsUnauthenticated(callMe(""));
        assertRejectedAsUnauthenticated(mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer")));
        assertRejectedAsUnauthenticated(mockMvc.perform(get("/api/v1/me").header("Authorization", "Token abc")));
    }

    @Test
    void anUnsignedAlgNoneTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"iss\":\"bookworm\",\"sub\":\"" + userId(email)
                + "\",\"role\":\"ADMIN\",\"iat\":" + Instant.now().getEpochSecond() + ",\"exp\":"
                + Instant.now().plusSeconds(3600).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));

        assertRejectedAsUnauthenticated(callMe(header + "." + payload + "."));
        assertRejectedAsUnauthenticated(callMe(header + "." + payload));
    }

    @Test
    void aTokenSignedWithTheWrongSecretIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        byte[] attackerKey = new byte[48];
        new SecureRandom().nextBytes(attackerKey);

        String forged = hmacToken(JWSAlgorithm.HS256, attackerKey,
                nimbusClaims(String.valueOf(userId(email)), "ADMIN", Instant.now().plusSeconds(3600)).build());

        assertRejectedAsUnauthenticated(callMe(forged));
    }

    @Test
    void aTokenUsingADifferentHmacAlgorithmIsRejectedEvenWithTheRealKey() throws Exception {
        // Only HS256 is accepted (algorithm pinning): HS384/HS512 tokens signed with the real secret fail.
        // First prove the hand-built helper is sound: the same construction with HS256 IS accepted.
        String email = uniqueEmail();
        register(email, PASSWORD);
        String id = String.valueOf(userId(email));

        callMe(manualHmacToken("HS256", "HmacSHA256", id)).andExpect(status().isOk());
        assertRejectedAsUnauthenticated(callMe(manualHmacToken("HS384", "HmacSHA384", id)));
        assertRejectedAsUnauthenticated(callMe(manualHmacToken("HS512", "HmacSHA512", id)));
    }

    /** Builds and signs a JWT by hand, because Nimbus refuses to sign HS512 with the 48-byte test key. */
    private String manualHmacToken(String jwsAlg, String macAlg, String subject) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(("{\"alg\":\"" + jwsAlg + "\",\"typ\":\"JWT\"}")
                .getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"iss\":\"bookworm\",\"sub\":\"" + subject
                + "\",\"role\":\"CUSTOMER\",\"iat\":" + Instant.now().getEpochSecond() + ",\"exp\":"
                + Instant.now().plusSeconds(3600).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance(macAlg);
        mac.init(new javax.crypto.spec.SecretKeySpec(TestJwtSecretInitializer.SECRET_BYTES, macAlg));
        String signature = enc.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + signature;
    }

    @Test
    void aTamperedPayloadWithTheOriginalSignatureIsRejected() throws Exception {
        String email = uniqueEmail();
        String token = registerAndLogin(email);
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"bookworm\",\"sub\":\"" + userId(email) + "\",\"role\":\"ADMIN\",\"iat\":"
                        + Instant.now().getEpochSecond() + ",\"exp\":" + Instant.now().plusSeconds(99999)
                        .getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));

        assertRejectedAsUnauthenticated(callMe(parts[0] + "." + forgedPayload + "." + parts[2]));
        // And a flipped signature byte.
        String badSig = parts[2].substring(0, parts[2].length() - 2) + (parts[2].endsWith("AA") ? "BB" : "AA");
        assertRejectedAsUnauthenticated(callMe(parts[0] + "." + parts[1] + "." + badSig));
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        Instant now = Instant.now();
        String expired = signedToken(JwtClaimsSet.builder().issuer("bookworm").subject(String.valueOf(userId(email)))
                .claim("role", "CUSTOMER").issuedAt(now.minus(Duration.ofHours(3)))
                .expiresAt(now.minus(Duration.ofHours(2))).build());

        assertRejectedAsUnauthenticated(callMe(expired));
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        Instant now = Instant.now();
        String wrongIssuer = signedToken(JwtClaimsSet.builder().issuer("someone-else")
                .subject(String.valueOf(userId(email))).claim("role", "CUSTOMER").issuedAt(now)
                .expiresAt(now.plusSeconds(3600)).build());

        assertRejectedAsUnauthenticated(callMe(wrongIssuer));
    }

    @Test
    void aTokenWithoutAnExpiryIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String noExp = hmacToken(JWSAlgorithm.HS256, TestJwtSecretInitializer.SECRET_BYTES,
                new JWTClaimsSet.Builder().issuer("bookworm").subject(String.valueOf(userId(email)))
                        .claim("role", "CUSTOMER").issueTime(new Date()).build());

        assertRejectedAsUnauthenticated(callMe(noExp));
    }

    @Test
    void badSubjectsAndRolesAreRejectedEvenWhenCorrectlySigned() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String id = String.valueOf(userId(email));

        assertRejectedAsUnauthenticated(callMe(signedToken(claims(null, "CUSTOMER").build())));      // no sub
        assertRejectedAsUnauthenticated(callMe(signedToken(claims("abc", "CUSTOMER").build())));     // not numeric
        assertRejectedAsUnauthenticated(callMe(signedToken(claims("-1", "CUSTOMER").build())));      // negative
        assertRejectedAsUnauthenticated(callMe(signedToken(claims("0", "CUSTOMER").build())));       // zero
        assertRejectedAsUnauthenticated(callMe(signedToken(claims(id + " OR 1=1", "CUSTOMER").build())));
        assertRejectedAsUnauthenticated(callMe(signedToken(claims(id, null).build())));              // no role
        assertRejectedAsUnauthenticated(callMe(signedToken(claims(id, "SUPERUSER").build())));       // unknown role
        assertRejectedAsUnauthenticated(callMe(signedToken(claims(id, "admin").build())));           // wrong case
        assertRejectedAsUnauthenticated(callMe(signedToken(claims("99999999999", "CUSTOMER").build()))); // no such user
    }

    // ---- token transport --------------------------------------------------------------------------

    @Test
    void tokensInTheQueryStringBodyOrCookiesAreIgnored() throws Exception {
        String token = registerAndLogin(uniqueEmail());

        assertRejectedAsUnauthenticated(mockMvc.perform(get("/api/v1/me").param("access_token", token)));
        assertRejectedAsUnauthenticated(mockMvc.perform(post("/api/v1/me/password").param("access_token", token)));
        assertRejectedAsUnauthenticated(mockMvc.perform(get("/api/v1/me")
                .cookie(new jakarta.servlet.http.Cookie("access_token", token))));
        assertRejectedAsUnauthenticated(mockMvc.perform(get("/api/v1/me").header("X-Auth-Token", token)));
    }

    @Test
    void httpBasicCredentialsAreNotAccepted() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String basic = Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Basic " + basic))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void noSessionCookieIsEverIssued() throws Exception {
        String token = registerAndLogin(uniqueEmail());

        mockMvc.perform(me(token)).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
    }

    // ---- current-user validation (clarification 1) --------------------------------------------------

    @Test
    void aDeletedUsersValidTokenIsRejectedOnTheNextRequest() throws Exception {
        String email = uniqueEmail();
        String token = registerAndLogin(email);
        callMe(token).andExpect(status().isOk());

        jdbc.update("delete from users where email = ?", email);

        assertRejectedAsUnauthenticated(callMe(token));
    }

    @Test
    void aDisabledUsersValidTokenIsRejectedOnTheNextRequestAndWorksAgainWhenReEnabled() throws Exception {
        String email = uniqueEmail();
        String token = registerAndLogin(email);
        callMe(token).andExpect(status().isOk());

        jdbc.update("update users set enabled = false where email = ?", email);
        assertRejectedAsUnauthenticated(callMe(token));
        assertRejectedAsUnauthenticated(mockMvc.perform(bearer(jsonPost("/api/v1/me/password",
                json("currentPassword", PASSWORD, "newPassword", "a brand new password")), token)));

        jdbc.update("update users set enabled = true where email = ?", email);
        callMe(token).andExpect(status().isOk());
    }

    @Test
    void aPromotionTakesEffectOnTheNextRequestWithTheOldToken() throws Exception {
        String email = uniqueEmail();
        String token = registerAndLogin(email);
        mockMvc.perform(bearer(get("/api/v1/admin/probe"), token)).andExpect(status().isForbidden());

        jdbc.update("update users set role = 'ADMIN' where email = ?", email);

        // The token still says CUSTOMER, yet the current database role (ADMIN) is what authorizes the call.
        mockMvc.perform(bearer(get("/api/v1/admin/probe"), token)).andExpect(status().isOk());
    }

    @Test
    void aDemotionTakesEffectOnTheNextRequestWithTheOldAdminToken() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        jdbc.update("update users set role = 'ADMIN' where email = ?", email);
        String adminToken = tokenFrom(login(email, PASSWORD));
        mockMvc.perform(bearer(get("/api/v1/admin/probe"), adminToken)).andExpect(status().isOk());

        jdbc.update("update users set role = 'CUSTOMER' where email = ?", email);

        mockMvc.perform(bearer(get("/api/v1/admin/probe"), adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void theRoleClaimInsideATokenIsNeverTrustedForAuthorization() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        // Correctly signed with the real key and claiming ADMIN, but the account is a CUSTOMER.
        String lyingToken = signedToken(claims(String.valueOf(userId(email)), "ADMIN").build());

        mockMvc.perform(bearer(get("/api/v1/admin/probe"), lyingToken)).andExpect(status().isForbidden());
        callMe(lyingToken).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    // ---- authorization ----------------------------------------------------------------------------

    @Test
    void anonymousUsersCannotReachProtectedOrAdminRoutesOrUnknownPaths() throws Exception {
        assertRejectedAsUnauthenticated(mockMvc.perform(get("/api/v1/me")));
        assertRejectedAsUnauthenticated(mockMvc.perform(get("/api/v1/admin/probe")));
        assertRejectedAsUnauthenticated(mockMvc.perform(get("/api/v1/me/anything")));
        assertRejectedAsUnauthenticated(mockMvc.perform(get("/api/v1/does-not-exist")));
        assertRejectedAsUnauthenticated(mockMvc.perform(post("/api/v1/me/password")));
        assertRejectedAsUnauthenticated(mockMvc.perform(get("/actuator/env")));
    }

    @Test
    void aCustomerGetsForbiddenOnAdminRoutesAndAnAdminSucceeds() throws Exception {
        String customer = uniqueEmail();
        String customerToken = registerAndLogin(customer);
        String admin = uniqueEmail();
        register(admin, PASSWORD);
        jdbc.update("update users set role = 'ADMIN' where email = ?", admin);
        String adminToken = tokenFrom(login(admin, PASSWORD));

        mockMvc.perform(bearer(get("/api/v1/admin/probe"), customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mockMvc.perform(bearer(get("/api/v1/admin/probe"), adminToken))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result
                        .MockMvcResultMatchers.content().string("admin-ok"));
    }

    @Test
    void publicEndpointsStayOpenAndErrorBodiesLeakNothing() throws Exception {
        mockMvc.perform(get("/api/v1/books")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/v1/me")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "at com.", "org.springframework", "jwt", "Bearer error");
        mockMvc.perform(get("/api/v1/me")).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
