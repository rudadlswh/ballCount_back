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
    void productionConfigurationAllowsPushDisabledWhenSchedulersAreDisabled() {
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
    void productionConfigurationRejectsSchedulerEnabledWithPushDisabled() {
        ApnsProperties properties = configuredProperties();
        properties.setPushEnabled(false);
        SchedulerShellProperties schedulerProperties = schedulerProperties(true);

        assertThatThrownBy(() -> guard(properties, schedulerProperties, new LiveSyncProperties()).validateProductionConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production notification runtime requires KBO_PUSH_ENABLED=true when APP_SCHEDULER_ENABLED=true or KBO_LIVE_SYNC_ENABLED=true.");
    }

    @Test
    void productionConfigurationRejectsLiveSyncEnabledWithPushDisabled() {
        ApnsProperties properties = configuredProperties();
        properties.setPushEnabled(false);
        LiveSyncProperties liveSyncProperties = new LiveSyncProperties();
        liveSyncProperties.setEnabled(true);

        assertThatThrownBy(() -> guard(properties, new SchedulerShellProperties(), liveSyncProperties).validateProductionConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production notification runtime requires KBO_PUSH_ENABLED=true when APP_SCHEDULER_ENABLED=true or KBO_LIVE_SYNC_ENABLED=true.");
    }

    @Test
    void productionConfigurationAllowsPushEnabledWithSchedulerEnabled() {
        ApnsProperties properties = configuredProperties();
        SchedulerShellProperties schedulerProperties = schedulerProperties(true);

        assertThatCode(() -> guard(properties, schedulerProperties, new LiveSyncProperties()).validateProductionConfiguration())
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

    private SchedulerShellProperties schedulerProperties(boolean enabled) {
        SchedulerShellProperties properties = new SchedulerShellProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private ApnsProductionConfigurationGuard guard(ApnsProperties properties) {
        return guard(properties, new SchedulerShellProperties(), new LiveSyncProperties());
    }

    private ApnsProductionConfigurationGuard guard(
            ApnsProperties properties,
            SchedulerShellProperties schedulerProperties,
            LiveSyncProperties liveSyncProperties
    ) {
        return new ApnsProductionConfigurationGuard(properties, schedulerProperties, liveSyncProperties);
    }
}
