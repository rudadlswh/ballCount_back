package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class AppliedMigrationIntegrityTest {

    @Test
    void appliedV34MigrationRemainsImmutable() throws Exception {
        try (InputStream migration = getClass().getResourceAsStream(
                "/db/migration/V34__add_monitored_game_to_notification_devices.sql"
        )) {
            assertThat(migration).isNotNull();
            String checksum = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(migration.readAllBytes())
            );
            assertThat(checksum).isEqualTo("88d6f3f36b97d04e60c5f7c39f391bc0e7014a7f399e94eb9860ab9366fc1dd1");
        }
    }
}
