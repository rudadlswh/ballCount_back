package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApnsProductionConfigurationGuardTest {

    @Test
    void productionConfigurationIsAcceptedWhenAllRequiredValuesArePresent() {
        ApnsProperties properties = configuredProperties();

        guard(properties).validateProductionConfiguration();
    }

    @Test
    void productionConfigurationRejectsSandboxEnvironmentWhenPushEnabled() {
        ApnsProperties properties = configuredProperties();
        properties.setEnv("sandbox");

        assertThatThrownBy(() -> guard(properties).validateProductionConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production APNs requires APNS_ENV=production.");
    }

    @Test
    void productionConfigurationAllowsPushDisabledWhenSyncIsDisabled() {
        ApnsProperties properties = configuredProperties();
        properties.setPushEnabled(false);
        properties.setEnv("sandbox");
        properties.setTeamId("");
        properties.setKeyId("");
        properties.setPrivateKeyPath("");

        assertThatCode(() -> guard(properties).validateProductionConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void productionConfigurationRejectsSyncEnabledWithPushDisabled() {
        ApnsProperties properties = configuredProperties();
        properties.setPushEnabled(false);

        assertThatThrownBy(() -> guard(properties, syncProperties(true)).validateProductionConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production notification runtime requires KBO_PUSH_ENABLED=true when APP_SYNC_ENABLED=true.");
    }

    @Test
    void productionConfigurationAllowsPushEnabledWithSyncEnabled() {
        ApnsProperties properties = configuredProperties();

        assertThatCode(() -> guard(properties, syncProperties(true)).validateProductionConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void productionConfigurationRequiresPrivateKeyPathWhenPushEnabled() {
        ApnsProperties properties = configuredProperties();
        properties.setPrivateKeyPath("");

        assertThatThrownBy(() -> guard(properties).validateProductionConfiguration())
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

    private ApnsProductionConfigurationGuard guard(ApnsProperties properties) {
        return guard(properties, syncProperties(false));
    }

    private ApnsProductionConfigurationGuard guard(
            ApnsProperties properties,
            SyncProperties syncProperties
    ) {
        return new ApnsProductionConfigurationGuard(properties, syncProperties);
    }

    private SyncProperties syncProperties(boolean enabled) {
        SyncProperties properties = new SyncProperties();
        properties.setEnabled(enabled);
        return properties;
    }
}
