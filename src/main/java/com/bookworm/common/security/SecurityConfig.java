package com.bookworm.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless, deny-by-default JWT resource server.
 * <ul>
 *   <li>No session, no cookie, no form login, no HTTP Basic. CSRF protection is disabled ONLY because credentials
 *       travel in the {@code Authorization} header and never in cookies.</li>
 *   <li>Tokens are accepted only from the {@code Authorization: Bearer} header (not the query string or body).</li>
 *   <li>Every route not listed below requires authentication, so an anonymous request to an unknown path gets
 *       401 rather than 404.</li>
 *   <li>Swagger and the OpenAPI document are open only when {@code springdoc.api-docs.enabled=true} (dev, test).</li>
 *   <li>No CORS configuration: browsers' cross-origin calls stay blocked until Phase 10 reviews it.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            ProblemAuthenticationEntryPoint entryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler,
            CurrentUserJwtConverter jwtConverter,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(j -> j.jwtAuthenticationConverter(jwtConverter)))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll();
                    a.requestMatchers(HttpMethod.GET, "/api/v1/books", "/api/v1/books/*", "/api/v1/books/*/related",
                            "/api/v1/categories").permitAll();
                    a.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll();
                    a.requestMatchers("/error").permitAll();
                    if (apiDocsEnabled) {
                        a.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
                                "/swagger-ui/**").permitAll();
                    }
                    a.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
                    // Phase 4: the cart and wishlist belong to customers; an ADMIN gets 403. The rest of /me/** stays
                    // open to any authenticated user.
                    a.requestMatchers("/api/v1/me/cart/**", "/api/v1/me/wishlist/**", "/api/v1/me/orders/**", "/api/v1/me/points")
                            .hasRole("CUSTOMER");
                    a.anyRequest().authenticated();
                });
        return http.build();
    }
}
