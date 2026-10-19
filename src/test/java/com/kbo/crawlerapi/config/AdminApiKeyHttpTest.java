package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.api.PublicPageController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = AdminApiKeyHttpTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.security.admin-api-key=test-admin-key", "spring.config.import=",
                "server.servlet.context-path=/test", "springdoc.api-docs.enabled=false"})
class AdminApiKeyHttpTest {
    @LocalServerPort
    int port;

    @Test
    void normalizedAdminAndInternalPathsAlwaysRequireTheKey() throws Exception {
        for (String path : new String[] {"/admin/probe", "/%61dmin/probe", "/admin;x/probe",
                "/internal/probe", "/%69nternal/probe", "/internal;x/probe"}) {
            assertThat(send(path, null).statusCode()).as(path).isEqualTo(401);
            assertThat(send(path, "wrong-key").statusCode()).as(path).isEqualTo(401);
            assertThat(send(path, "test-admin-key").statusCode()).as(path).isEqualTo(200);
        }
    }

    @Test
    void publicPagesRemainAccessibleWithHeadersAndStyles() throws Exception {
        for (String path : new String[] {"/support", "/privacy"}) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(url(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
            assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow())
                    .contains("style-src 'sha256-", "frame-ancestors 'none'")
                    .doesNotContain("unsafe-inline");
            assertThat(response.body()).contains("<style>");
            String css = response.body().split("<style>", 2)[1].split("</style>", 2)[0];
            String hash = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(css.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow()).contains(hash);
        }
    }

    private HttpResponse<String> send(String path, String key) throws Exception {
        var request = HttpRequest.newBuilder(url(path)).POST(HttpRequest.BodyPublishers.noBody());
        if (key != null) {
            request.header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, key);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void preservesJackson2NamingAndIsoDatesOnTheWire() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(url("/json-probe")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"game_name\":\"sample\"", "\"updated_at\":\"2026-10-19T09:00:00+09:00\"");
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + "/test" + path);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({AdminApiKeyFilterConfig.class, AppSecurityProperties.class, SecurityHeadersConfig.class,
            PublicPageController.class, ProbeController.class})
    static class TestApp { }

    @RestController
    static class ProbeController {
        @PostMapping({"/admin/probe", "/internal/probe"})
        String probe() { return "ok"; }

        @org.springframework.web.bind.annotation.GetMapping("/json-probe")
        JsonProbe jsonProbe() {
            return new JsonProbe("sample", java.time.OffsetDateTime.parse("2026-10-19T09:00:00+09:00"));
        }
    }

    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    record JsonProbe(String gameName, java.time.OffsetDateTime updatedAt) { }
}
