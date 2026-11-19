package com.bookworm.common.security;

import com.bookworm.common.error.ErrorCode;
import com.bookworm.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Writes an RFC 9457 problem body from inside the security filter chain, where the MVC exception handler
 * is not reached. Only fixed, server-defined strings are written (never request data or exception text).
 */
@Component
public class ProblemResponseWriter {

    public void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        StringBuilder json = new StringBuilder(256)
                .append("{\"type\":\"about:blank\",\"title\":\"").append(escape(code.title()))
                .append("\",\"status\":").append(code.status().value())
                .append(",\"detail\":\"").append(escape(detail))
                .append("\",\"code\":\"").append(code.name()).append('"');
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null) {
            json.append(",\"traceId\":\"").append(escape(traceId)).append('"');
        }
        json.append('}');
        response.getWriter().write(json.toString());
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
