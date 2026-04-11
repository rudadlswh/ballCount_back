package com.kbo.crawlerapi.config;

import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "KBO Crawler API",
                description = "Internal/app-facing API for normalized KBO schedule, detail, and scoreboard data.",
                version = "v1",
                contact = @Contact(
                        name = "KBO Crawler API Maintainers",
                        email = "maintainers@example.com"
                )
        )
)
public class OpenApiConfig {
}
