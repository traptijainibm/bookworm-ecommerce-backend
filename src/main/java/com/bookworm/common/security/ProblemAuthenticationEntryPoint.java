package com.bookworm.common.security;

import com.bookworm.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 401 for a missing, malformed, forged, expired or otherwise invalid token. The response never says which of
 * those it was; only the exception type is logged server-side (never the token).
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ProblemAuthenticationEntryPoint.class);

    private final ProblemResponseWriter writer;

    public ProblemAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.warn("Authentication rejected: reason={}", authException.getClass().getSimpleName());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writer.write(response, ErrorCode.UNAUTHENTICATED, "Authentication is required to access this resource.");
    }
}
