package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminLoginRateLimitFilterTest {
    @Test
    void clientAndGlobalLimitsIgnoreUsernameAndForwardedHeadersAndExpire() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setAdminLoginMaxAttempts(2);
        properties.setAdminLoginGlobalMaxAttempts(3);
        ClientRequestLimiterTest.MutableClock clock = new ClientRequestLimiterTest.MutableClock();
        AdminLoginRateLimitFilter filter = new AdminLoginRateLimitFilter(properties, clock);
        assertThat(attempt(filter, "one", "POST")).isEqualTo(200);
        assertThat(attempt(filter, "one", "POST")).isEqualTo(200);
        assertThat(attempt(filter, "one", "POST")).isEqualTo(429);
        assertThat(attempt(filter, "two", "POST")).isEqualTo(200);
        assertThat(attempt(filter, "three", "POST")).isEqualTo(429);
        assertThat(attempt(filter, "one", "GET")).isEqualTo(200);
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThat(attempt(filter, "one", "POST")).isEqualTo(200);
    }

    private int attempt(AdminLoginRateLimitFilter filter, String ip, String method) throws Exception {
        var request = new MockHttpServletRequest(method, "/admin/login");
        request.setRemoteAddr(ip);
        request.addHeader("X-Forwarded-For", java.util.UUID.randomUUID().toString());
        request.addParameter("username", java.util.UUID.randomUUID().toString());
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        if (response.getStatus() == 429) assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        return response.getStatus();
    }
}
