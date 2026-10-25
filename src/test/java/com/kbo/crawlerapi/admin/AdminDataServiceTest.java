package com.kbo.crawlerapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.scheduler.LiveGameSyncScheduler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminDataServiceTest {
    @ParameterizedTest
    @CsvSource({
            "2026-11-30T14:59:59Z, true, 실행 중, success",
            "2026-11-30T15:00:00Z, true, 비시즌 대기, muted",
            "2027-02-28T15:00:00Z, true, 실행 중, success",
            "2027-02-28T15:00:00Z, false, 비활성, muted"
    })
    void dashboardShowsEffectiveSeasonState(String instant, boolean enabled, String status, String tone) {
        var sync = new SyncProperties();
        sync.setEnabled(enabled);
        var scheduler = mock(LiveGameSyncScheduler.class);
        when(scheduler.isRunning()).thenReturn(true);
        var jdbc = mock(JdbcTemplate.class);
        // Skip unrelated operational queries while checking the scheduler badge.
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(0);
        var service = new AdminDataService(jdbc, sync, new LiveSyncProperties(), scheduler,
                new AdminUiProperties(), mock(AdminAuthenticationService.class), mock(Environment.class),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));

        var dashboard = service.dashboard();
        assertThat(dashboard.schedulerStatus()).isEqualTo(status);
        assertThat(dashboard.schedulerTone()).isEqualTo(tone);
    }
}
