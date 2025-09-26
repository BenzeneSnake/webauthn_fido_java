package com.webauthn.app.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI webauthnOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("WebAuthn FIDO Demo API")
                        .description("WebAuthn FIDO 認證系統的 REST API 文檔")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("WebAuthn Demo")
                                .email("demo@webauthn.com")));
    }
}