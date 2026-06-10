package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiveActivityTokenRegistrationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-05T10:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private LiveActivityTokenRepository repository;

    @Test
    void tokenDedupeUpdatesLastSeenAndKeepsExistingRow() {
        LiveActivityToken existing = new LiveActivityToken(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "old-activity",
                "ios",
                "sandbox",
                "activity-token",
                "install-1",
                "hanwha",
                "20260605-LOT-HAN",
                "20260605HHLT0",
                "22222222-2222-2222-2222-222222222222",
                "provider:20260605HHLT0",
                OffsetDateTime.parse("2026-06-05T18:00:00+09:00")
        );
        when(repository.findRegistrationMatches(
                eq("sandbox"),
                eq("activity-token"),
                eq("20260605-LOT-HAN"),
                eq("20260605HHLT0"),
                eq("22222222-2222-2222-2222-222222222222"),
                eq("provider:20260605HHLT0")
        )).thenReturn(List.of(existing));
        LiveActivityTokenRegistrationService service = new LiveActivityTokenRegistrationService(repository, CLOCK);

        var result = service.register(command("activity-token"));

        ArgumentCaptor<LiveActivityToken> captor = ArgumentCaptor.forClass(LiveActivityToken.class);
        verify(repository).save(captor.capture());
        assertThat(result.id()).isEqualTo(existing.getId());
        assertThat(captor.getValue().getActivityId()).isEqualTo("new-activity");
        assertThat(captor.getValue().getLastSeenAt()).isEqualTo(OffsetDateTime.now(CLOCK));
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void newTokenRegistrationCreatesActiveRow() {
        when(repository.findRegistrationMatches(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        LiveActivityTokenRegistrationService service = new LiveActivityTokenRegistrationService(repository, CLOCK);

        var result = service.register(command("new-activity-token"));

        ArgumentCaptor<LiveActivityToken> captor = ArgumentCaptor.forClass(LiveActivityToken.class);
        verify(repository).save(captor.capture());
        assertThat(result.active()).isTrue();
        assertThat(captor.getValue().getActivityToken()).isEqualTo("new-activity-token");
        assertThat(captor.getValue().isActive()).isTrue();
    }

    private LiveActivityTokenRegistrationService.LiveActivityTokenRegistrationCommand command(String activityToken) {
        return new LiveActivityTokenRegistrationService.LiveActivityTokenRegistrationCommand(
                "new-activity",
                "ios",
                "sandbox",
                activityToken,
                "install-1",
                "hanwha",
                "20260605-LOT-HAN",
                "20260605HHLT0",
                "22222222-2222-2222-2222-222222222222",
                "provider:20260605HHLT0"
        );
    }
}
