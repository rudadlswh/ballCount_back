package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "KBO_TOKEN_TEST_DATABASE_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:.*")
class TokenRotationMigrationTest {
    @Test
    void activeSchemaAllowsRotatedTokensAndRetainsFullTokenUniqueness() throws Exception {
        String url = System.getenv("KBO_TOKEN_TEST_DATABASE_URL");
        String schema = "token_rotation_test_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, "codex", ""));
        admin.execute("CREATE SCHEMA " + schema);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=" + schema, "codex", ""));
        try {
            for (String name : List.of("V1__init_core_schema.sql", "V7__add_live_sync_notifications.sql",
                    "V10__add_apns_environment_to_notification_devices.sql", "V15__add_live_activity_tokens.sql",
                    "V16__add_live_activity_activity_token_column.sql", "V17__fix_live_activity_token_column_constraints.sql",
                    "V22__add_notification_device_installation_environment_unique_index.sql",
                    "V23__add_live_activity_push_to_start_tokens.sql")) {
                jdbc.execute(sql(name).replace("kbo_crawler_api.", schema + ".").replace("'kbo_crawler_api'", "'" + schema + "'"));
            }
            insertTokens(jdbc, "old-token");
            assertThatThrownBy(() -> insertTokens(jdbc, "new-token"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            jdbc.execute(sql("V40__allow_capability_scoped_token_rotation.sql").replace("${appSchema}", schema));
            jdbc.execute(sql("V41__complete_snapshot_and_notification_schema.sql").replace("${appSchema}", schema));
            insertTokens(jdbc, "new-token");
            for (String table : List.of("notification_devices", "live_activity_tokens", "live_activity_push_to_start_tokens")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isEqualTo(2);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_devices WHERE device_token='old-token' AND notifications_enabled", Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> insertDevice(jdbc, "new-token")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertActivity(jdbc, "new-token")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertPushToStart(jdbc, "new-token")).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private String sql(String name) throws Exception {
        return Files.readString(Path.of("src/main/resources/db/migration", name)).replace("\uFEFF", "");
    }

    private void insertTokens(JdbcTemplate jdbc, String token) {
        insertDevice(jdbc, token);
        insertActivity(jdbc, token);
        insertPushToStart(jdbc, token);
    }

    private void insertDevice(JdbcTemplate jdbc, String token) {
        jdbc.update("INSERT INTO notification_devices(id,platform,environment,device_token,installation_id) VALUES (?,'ios','sandbox',?,'same-installation')", UUID.randomUUID(), token);
    }

    private void insertActivity(JdbcTemplate jdbc, String token) {
        jdbc.update("INSERT INTO live_activity_tokens(id,activity_id,platform,environment,activity_token,installation_id) VALUES (?,'same-activity','ios','sandbox',?,'same-installation')", UUID.randomUUID(), token);
    }

    private void insertPushToStart(JdbcTemplate jdbc, String token) {
        jdbc.update("INSERT INTO live_activity_push_to_start_tokens(id,platform,environment,push_to_start_token,installation_id) VALUES (?,'ios','sandbox',?,'same-installation')", UUID.randomUUID(), token);
    }
}
