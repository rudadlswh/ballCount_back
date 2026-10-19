package com.kbo.crawlerapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdminAuthenticationServiceTest {

    @Test
    void authenticatesOnlyExactConfiguredCredentials() {
        AdminUiProperties properties = new AdminUiProperties();
        properties.setUsername("operator");
        properties.setPassword("very-secret");
        AdminAuthenticationService service = new AdminAuthenticationService(properties);

        assertThat(service.authenticate("operator", "very-secret")).isTrue();
        assertThat(service.authenticate("Operator", "very-secret")).isFalse();
        assertThat(service.authenticate("operator", "wrong")).isFalse();
        assertThat(service.authenticate(null, null)).isFalse();
    }

    @Test
    void blankPasswordDisablesLoginAndValuesAreMasked() {
        AdminUiProperties properties = new AdminUiProperties();
        properties.setUsername("operator");
        properties.setPassword(" ");
        AdminAuthenticationService service = new AdminAuthenticationService(properties);

        assertThat(service.isConfigured()).isFalse();
        assertThat(service.authenticate("operator", " ")).isFalse();
        assertThat(service.maskedUsername()).isEqualTo("o*******");
        assertThat(service.maskedPassword()).isEqualTo("미설정");
    }

    @Test
    void sessionTimeoutHasSafeMinimum() {
        AdminUiProperties properties = new AdminUiProperties();
        properties.setSessionTimeout(Duration.ofSeconds(1));

        assertThat(new AdminAuthenticationService(properties).sessionTimeoutSeconds()).isEqualTo(60);
    }
}
