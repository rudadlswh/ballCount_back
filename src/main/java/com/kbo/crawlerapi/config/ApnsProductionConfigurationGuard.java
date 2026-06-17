package com.kbo.crawlerapi.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
public class ApnsProductionConfigurationGuard {

    private static final String EXPECTED_BUNDLE_ID = "com.chogm.kboScore";

    private final ApnsProperties apnsProperties;

    public ApnsProductionConfigurationGuard(ApnsProperties apnsProperties) {
        this.apnsProperties = apnsProperties;
    }

    @PostConstruct
    public void validateProductionConfiguration() {
        if (!apnsProperties.isPushEnabled()) {
            throw new IllegalStateException("Production APNs requires KBO_PUSH_ENABLED=true.");
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
