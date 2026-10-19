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
    private final SyncProperties syncProperties;

    public ApnsProductionConfigurationGuard(
            ApnsProperties apnsProperties,
            SyncProperties syncProperties
    ) {
        this.apnsProperties = apnsProperties;
        this.syncProperties = syncProperties;
    }

    @PostConstruct
    public void validateProductionConfiguration() {
        boolean syncEnabled = syncProperties.isEnabled();
        boolean pushEnabled = apnsProperties.isPushEnabled();
        if (syncEnabled && !pushEnabled) {
            log.error(
                    "Production notification runtime misconfigured: syncEnabled={} pushEnabled={} configuredEnv={} bundleIdPresent={}",
                    syncEnabled,
                    pushEnabled,
                    apnsProperties.getEnv(),
                    apnsProperties.hasBundleId()
            );
            throw new IllegalStateException(
                    "Production notification runtime requires KBO_PUSH_ENABLED=true when APP_SYNC_ENABLED=true."
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
