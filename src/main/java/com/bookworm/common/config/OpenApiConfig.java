package com.bookworm.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bookwormOpenApi() {
        return new OpenAPI().info(new Info()
                .title("BookWorm eCommerce API")
                .description("Backend for the BookWorm online bookstore")
                .version("v1"));
    }
}
