package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SyncPropertiesTest {
    @ParameterizedTest
    @CsvSource({
            "2026-11-30T14:59:59Z, false",
            "2026-11-30T15:00:00Z, true",
            "2027-01-15T00:00:00Z, true",
            "2027-02-15T00:00:00Z, true",
            "2027-02-28T14:59:59Z, true",
            "2027-02-28T15:00:00Z, false",
            "2027-11-30T15:00:00Z, true",
            "2028-02-29T00:00:00Z, true",
            "2028-02-29T14:59:59Z, true",
            "2028-02-29T15:00:00Z, false"
    })
    void followsKoreanSeasonBoundariesWithoutOverridingConfiguredEnablement(String instant, boolean offSeason) {
        var clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        var properties = new SyncProperties();
        assertThat(properties.isOffSeason(clock)).isEqualTo(offSeason);
        assertThat(properties.isActive(clock)).isFalse();
        properties.setEnabled(true);
        assertThat(properties.isActive(clock)).isEqualTo(!offSeason);
        // Startup guards must still validate the writer configuration during winter.
        assertThat(properties.isEnabled()).isTrue();
    }
}
