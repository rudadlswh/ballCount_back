package com.kbo.crawlerapi.config;

import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "KBO Crawler API",
                description = "정규화된 KBO 일정, 경기 상세, 스코어보드 데이터를 제공하는 내부 및 앱용 API입니다.",
                version = "v1",
                contact = @Contact(
                        name = "KBO Crawler API 운영팀",
                        email = "maintainers@example.com"
                )
        )
)
public class OpenApiConfig {
}
