package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApnsProductionConfigurationGuardTest {

    @Test
    void productionConfigurationIsAcceptedWhenAllRequiredValuesArePresent() {
        ApnsProperties properties = configuredProperties();

        new ApnsProductionConfigurationGuard(properties).validateProductionConfiguration();
    }

    @Test
    void productionConfigurationRejectsSandboxEnvironmentWhenPushEnabled() {
        ApnsProperties properties = configuredProperties();
        properties.setEnv("sandbox");

        assertThatThrownBy(() -> new ApnsProductionConfigurationGuard(properties).validateProductionConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production APNs requires APNS_ENV=production.");
    }

    @Test
    void productionConfigurationAllowsPushDisabled() {
        ApnsProperties properties = configuredProperties();
        properties.setPushEnabled(false);
        properties.setEnv("sandbox");
        properties.setTeamId("");
        properties.setKeyId("");
        properties.setPrivateKeyPath("");

        assertThatCode(() -> new ApnsProductionConfigurationGuard(properties).validateProductionConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void productionConfigurationRequiresPrivateKeyPathWhenPushEnabled() {
        ApnsProperties properties = configuredProperties();
        properties.setPrivateKeyPath("");

        assertThatThrownBy(() -> new ApnsProductionConfigurationGuard(properties).validateProductionConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production APNs requires APNS_PRIVATE_KEY_PATH.");
    }

    private ApnsProperties configuredProperties() {
        ApnsProperties properties = new ApnsProperties();
        properties.setPushEnabled(true);
        properties.setEnv("production");
        properties.setTeamId("TEAMID1234");
        properties.setKeyId("KEYID1234");
        properties.setBundleId("com.chogm.kboScore");
        properties.setPrivateKeyPath("/secure/AuthKey_KEYID1234.p8");
        return properties;
    }
}