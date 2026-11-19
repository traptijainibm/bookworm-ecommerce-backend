package com.bookworm.testsupport;

import com.bookworm.common.security.CurrentUserJwtConverter;
import com.bookworm.common.security.ProblemAccessDeniedHandler;
import com.bookworm.common.security.ProblemAuthenticationEntryPoint;
import com.bookworm.common.security.ProblemResponseWriter;
import com.bookworm.common.security.SecurityConfig;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Loads the real filter chain into a {@code @WebMvcTest} slice (slices skip {@code @Configuration} classes).
 * The test class must also declare {@code @MockitoBean} for {@code UserService} and {@code JwtDecoder}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({SecurityConfig.class, ProblemAuthenticationEntryPoint.class, ProblemAccessDeniedHandler.class,
        ProblemResponseWriter.class, CurrentUserJwtConverter.class})
public @interface WebSliceSecurity {
}
