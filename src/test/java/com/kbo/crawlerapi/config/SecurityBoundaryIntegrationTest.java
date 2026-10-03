package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.admin.AdminAuthenticationController;
import com.kbo.crawlerapi.admin.AdminAuthenticationService;
import com.kbo.crawlerapi.admin.AdminUiProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = SecurityBoundaryIntegrationTest.TestApplication.class,
        properties = {"server.servlet.context-path=/test", "app.security.admin-api-key=test-key",
                "app.security.registration-rate-limit-max-requests=1",
                "app.security.admin-login-max-attempts=3", "app.admin-ui.username=operator",
                "app.admin-ui.password=test-password"})
class SecurityBoundaryIntegrationTest {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void containerEquivalentProtectedPathsRequireAuthentication() throws Exception {
        for (String path : new String[]{"/admin/mutate", "/admin;x=1/mutate", "/%61dmin/mutate",
                "/internal;x=1/mutate", "/%69nternal/mutate", "/api/v1/games/reconcile-stale;x=1",
                "/games/reconcile-stale;x=1", "/api/v1/games/reconcile-%73tale"}) {
            assertThat(post(path, null, "").statusCode()).as(path).isEqualTo(401);
            assertThat(post(path, "test-key", "").statusCode()).as("valid key: " + path).isEqualTo(200);
        }
        assertThat(post("/admin/mutate", "wrong-key", "").statusCode()).isEqualTo(401);
    }

    @Test
    void equivalentRegistrationPathsAndAliasesShareQuota() throws Exception {
        assertThat(post("/devices/register", null, "").statusCode()).isEqualTo(200);
        assertThat(post("/devices;x=1/register", null, "").statusCode()).isEqualTo(429);
        assertThat(post("/%64evices/register", null, "").statusCode()).isEqualTo(429);
        assertThat(post("/devices/register;x=2", null, "").statusCode()).isEqualTo(429);
        assertThat(post("/devices/%72egister", null, "").statusCode()).isEqualTo(429);
        assertThat(post("/devices/live-activities/register", null, "").statusCode()).isEqualTo(200);
        assertThat(post("/live-activities/register", null, "").statusCode()).isEqualTo(429);
        assertThat(post("/live-activities;x=2/register", null, "").statusCode()).isEqualTo(429);
        assertThat(post("/devices/live-activities/push-to-start/register", null, "").statusCode()).isEqualTo(200);
        assertThat(post("/live-activities/push-to-start/register", null, "").statusCode()).isEqualTo(429);
    }

    @Test
    void attendanceMethodsShareQuota() throws Exception {
        assertThat(client.send(HttpRequest.newBuilder(uri("/api/v1/attendance")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(client.send(HttpRequest.newBuilder(uri("/api/v1/attendance;x=1")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
    }

    @Test
    void loginAttemptsAreBoundedAndLegitimateSessionWorks() throws Exception {
        HttpResponse<String> login = post("/admin/login", null, "username=operator&password=test-password");
        assertThat(login.statusCode()).isEqualTo(302);
        String cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        assertThat(client.send(HttpRequest.newBuilder(uri("/admin/dashboard;x=1"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(client.send(HttpRequest.newBuilder(uri("/admin/mutate"))
                .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(post("/admin;x=1/login", null, "username=other&password=wrong").statusCode()).isEqualTo(401);
        assertThat(post("/%61dmin/login", null, "username=third&password=wrong").statusCode()).isEqualTo(401);
        assertThat(post("/admin/login", null, "username=operator&password=wrong").statusCode()).isEqualTo(429);
        assertThat(client.send(HttpRequest.newBuilder(uri("/admin/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + "/test" + path); }

    private HttpResponse<String> post(String path, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded");
        if (key != null) request.header("X-Admin-Key", key);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
    @EnableConfigurationProperties({AppSecurityProperties.class, KboAdminProperties.class, AdminUiProperties.class})
    @Import({AdminApiKeyFilterConfig.class, RegistrationRateLimitFilterConfig.class, AdminLoginRateLimitFilterConfig.class,
            AdminAuthenticationController.class, AdminAuthenticationService.class, Endpoints.class})
    static class TestApplication { }

    @RestController
    static class Endpoints {
        @PostMapping({"/admin/mutate", "/internal/mutate", "/api/v1/games/reconcile-stale", "/games/reconcile-stale",
                "/devices/register", "/devices/live-activities/register", "/live-activities/register",
                "/devices/live-activities/push-to-start/register", "/live-activities/push-to-start/register"})
        String mutate() { return "ok"; }
        @RequestMapping("/api/v1/attendance") String attendance() { return "ok"; }
        @GetMapping("/admin/dashboard") String dashboard() { return "ok"; }
    }
}
