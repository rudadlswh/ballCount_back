package com.kbo.crawlerapi.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
public class ApnsProductionConfigurationGuard {

    private static final Logger log = LoggerFactory.getLogger(ApnsProductionConfigurationGuard.class);
    private static final String EXPECTED_BUNDLE_ID = "com.chogm.kboScore";

    private final ApnsProperties apnsProperties;
    private final SchedulerShellProperties schedulerShellProperties;
    private final LiveSyncProperties liveSyncProperties;

    public ApnsProductionConfigurationGuard(
            ApnsProperties apnsProperties,
            SchedulerShellProperties schedulerShellProperties,
            LiveSyncProperties liveSyncProperties
    ) {
        this.apnsProperties = apnsProperties;
        this.schedulerShellProperties = schedulerShellProperties;
        this.liveSyncProperties = liveSyncProperties;
    }

    @PostConstruct
    public void validateProductionConfiguration() {
        boolean schedulerEnabled = schedulerShellProperties.isEnabled();
        boolean liveSyncEnabled = liveSyncProperties.isEnabled();
        boolean pushEnabled = apnsProperties.isPushEnabled();
        if ((schedulerEnabled || liveSyncEnabled) && !pushEnabled) {
            log.error(
                    "Production notification runtime misconfigured: schedulerEnabled={} liveSyncEnabled={} pushEnabled={} configuredEnv={} bundleIdPresent={}",
                    schedulerEnabled,
                    liveSyncEnabled,
                    pushEnabled,
                    apnsProperties.getEnv(),
                    apnsProperties.hasBundleId()
            );
            throw new IllegalStateException(
                    "Production notification runtime requires KBO_PUSH_ENABLED=true when APP_SCHEDULER_ENABLED=true or KBO_LIVE_SYNC_ENABLED=true."
            );
        }

        if (!apnsProperties.isPushEnabled()) {
            return;
        }

        if (!"production".equalsIgnoreCase(apnsProperties.getEnv())) {
            throw new IllegalStateException("Production APNs requires APNS_ENV=production.");
        }
        if (!EXPECTED_BUNDLE_ID.equals(apnsProperties.getBundleId())) {
            throw new IllegalStateException("Production APNs requires APNS_BUNDLE_ID=" + EXPECTED_BUNDLE_ID + ".");
        }
        if (!apnsProperties.hasTeamId()) {
            throw new IllegalStateException("Production APNs requires APNS_TEAM_ID.");
        }
        if (!apnsProperties.hasKeyId()) {
            throw new IllegalStateException("Production APNs requires APNS_KEY_ID.");
        }
        if (!apnsProperties.hasPrivateKeyPath()) {
            throw new IllegalStateException("Production APNs requires APNS_PRIVATE_KEY_PATH.");
        }
    }
}
