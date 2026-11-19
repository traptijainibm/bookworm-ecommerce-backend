package com.bookworm.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /** Name referenced by {@code @SecurityRequirement} on protected operations; enables Swagger's Authorize button. */
    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI bookwormOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("BookWorm eCommerce API")
                        .description("Backend for the BookWorm online bookstore")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
