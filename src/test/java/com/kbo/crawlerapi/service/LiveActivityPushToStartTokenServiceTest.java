package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LiveActivityPushToStartToken;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.LiveActivityPushToStartTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiveActivityPushToStartTokenServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-05T10:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private LiveActivityPushToStartTokenRepository repository;

    @Test
    void registrationUpdatesExistingInstallationRow() {
        LiveActivityPushToStartToken existing = token("old-token", "lg");
        when(repository.findByPlatformAndEnvironmentAndPushToStartToken(eq("ios"), eq("sandbox"), eq("new-token")))
                .thenReturn(Optional.empty());
        when(repository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.of(existing));
        LiveActivityPushToStartTokenService service = service();

        var result = service.register(command("new-token", true, true));

        ArgumentCaptor<LiveActivityPushToStartToken> captor = ArgumentCaptor.forClass(LiveActivityPushToStartToken.class);
        verify(repository).save(captor.capture());
        assertThat(result.id()).isEqualTo(existing.getId());
        assertThat(captor.getValue().getPushToStartToken()).isEqualTo("new-token");
        assertThat(captor.getValue().isLiveActivityAutoStartEnabled()).isTrue();
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void duplicateStartIsSkippedAfterSuccessfulDelivery() {
        LiveActivityPushToStartToken token = token("start-token", "lg");
        RecordingApnsPushService apnsPushService = new RecordingApnsPushService();
        when(repository.findByActiveTrue()).thenReturn(List.of(token));
        LiveActivityPushToStartTokenService service = service(apnsPushService);

        var first = service.deliverStart(game());
        var second = service.deliverStart(game());

        assertThat(first.sentCount()).isEqualTo(1);
        assertThat(apnsPushService.sentCount).isEqualTo(1);
        assertThat(second.sentCount()).isZero();
        assertThat(second.skippedCount()).isEqualTo(1);
        assertThat(token.getLastStartedGameKey()).isEqualTo("public:20260605-lg-doosan");
    }

    @Test
    void disabledAutoStartSkipsWithoutSendingApns() {
        LiveActivityPushToStartToken token = token("start-token", "lg");
        RecordingApnsPushService apnsPushService = new RecordingApnsPushService();
        token.update("ios", "sandbox", "start-token", "install-1", "lg", true, true, false, true, false, OffsetDateTime.now(CLOCK));
        when(repository.findByActiveTrue()).thenReturn(List.of(token));
        LiveActivityPushToStartTokenService service = service(apnsPushService);

        var result = service.deliverStart(game());

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(apnsPushService.sentCount).isZero();
    }

    private LiveActivityPushToStartTokenService service() {
        return service(new RecordingApnsPushService());
    }

    private LiveActivityPushToStartTokenService service(RecordingApnsPushService apnsPushService) {
        return new LiveActivityPushToStartTokenService(repository, contentStateBuilder, apnsPushService, CLOCK);
    }

    private LiveActivityContentStateBuilder contentStateBuilder = new LiveActivityContentStateBuilder(null) {
        @Override
        public Map<String, Object> buildForFavoriteTeam(Game game, String favoriteTeamId, com.kbo.crawlerapi.domain.NotificationEvent event) {
            return Map.of("isPreGame", false, "favoriteScoreText", "3");
        }
    };

    private LiveActivityPushToStartTokenService.PushToStartTokenRegistrationCommand command(
            String token,
            boolean notificationsAuthorized,
            boolean autoStartEnabled
    ) {
        return new LiveActivityPushToStartTokenService.PushToStartTokenRegistrationCommand(
                "ios",
                "sandbox",
                token,
                "install-1",
                "lg",
                notificationsAuthorized,
                true,
                autoStartEnabled,
                true,
                false
        );
    }

    private LiveActivityPushToStartToken token(String token, String favoriteTeamId) {
        LiveActivityPushToStartToken value = new LiveActivityPushToStartToken(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                OffsetDateTime.now(CLOCK)
        );
        value.update("ios", "sandbox", token, "install-1", favoriteTeamId, true, true, true, true, false, OffsetDateTime.now(CLOCK));
        return value;
    }

    private Game game() {
        Team home = new Team(UUID.randomUUID(), "lg", "LG", "LG", "LG Twins", null);
        Team away = new Team(UUID.randomUUID(), "doosan", "두산", "두산", "Doosan Bears", null);
        return new Game(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "20260605-LG-DOOSAN",
                "kbo",
                "20260605LGDO0",
                LocalDate.of(2026, 6, 5),
                OffsetDateTime.parse("2026-06-05T18:30:00+09:00"),
                "잠실",
                GameStatus.LIVE,
                home,
                away,
                3,
                2,
                "5회초",
                false,
                false,
                null,
                null,
                OffsetDateTime.now(CLOCK)
        );
    }

    private static final class RecordingApnsPushService extends ApnsPushService {
        private int sentCount;

        private RecordingApnsPushService() {
            super(new ApnsProperties(), CLOCK);
        }

        @Override
        public boolean environmentMatches(String deviceEnvironment) {
            return "sandbox".equalsIgnoreCase(deviceEnvironment);
        }

        @Override
        public ApnsSendResult sendLiveActivityStart(
                Game game,
                LiveActivityPushToStartToken pushToStartToken,
                Map<String, Object> attributes,
                Map<String, Object> contentState
        ) {
            sentCount++;
            return ApnsSendResult.sentResult();
        }
    }
}
