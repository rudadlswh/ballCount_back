package com.kbo.crawlerapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AdminLogBufferTest {

    private AdminLogBuffer buffer;

    @BeforeEach
    void setUp() {
        AdminUiProperties properties = new AdminUiProperties();
        properties.setLogCapacity(100);
        buffer = new AdminLogBuffer(properties);
        buffer.register();
    }

    @AfterEach
    void tearDown() {
        buffer.unregister();
    }

    @Test
    void searchesByLevelGameIdAndFeature() {
        org.slf4j.Logger logger = LoggerFactory.getLogger("com.kbo.crawlerapi.service.LiveGameSyncService");
        logger.warn("[LiveGameSync] refresh delayed game=20260725-LG-KIA reason=timeout");

        var results = buffer.search(null, null, "WARN", "LG-KIA", "LiveGameSync");

        assertThat(results).anySatisfy(entry -> {
            assertThat(entry.gameId()).isEqualTo("20260725-LG-KIA");
            assertThat(entry.feature()).isEqualTo("LiveGameSyncService");
            assertThat(entry.level()).isEqualTo("WARN");
        });
    }

    @Test
    void timeFilterCanExcludeEntries() {
        LoggerFactory.getLogger("com.kbo.crawlerapi.service.AdminLogBufferTest").info("admin log search sample");

        assertThat(buffer.search(LocalDateTime.now().plusMinutes(1), null, null, null, null)).isEmpty();
    }

    @Test
    void masksSecretsBeforeExposingLogs() {
        LoggerFactory.getLogger("com.kbo.crawlerapi.service.SecretTest")
                .warn("delivery failed device_token=raw-secret-token authorization: Bearer BearerValue");

        assertThat(buffer.search(null, null, "WARN", null, "SecretTest"))
                .anySatisfy(entry -> {
                    assertThat(entry.message()).contains("device_token=••••••••");
                    assertThat(entry.message()).contains("authorization: ••••••••");
                    assertThat(entry.message()).doesNotContain("raw-secret-token", "BearerValue");
                });
    }
}
