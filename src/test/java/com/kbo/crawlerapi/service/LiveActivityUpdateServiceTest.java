package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiveActivityUpdateServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-05T10:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private LiveActivityTokenRepository repository;

    @Test
    void invalidLiveActivityApnsTokenMarksTokenInactive() {
        Game game = fixtureGame();
        NotificationEvent event = new NotificationEvent(UUID.randomUUID(), game, NotificationEventService.EVENT_SCORE_CHANGED, "event-key", "title", "body", "{}");
        LiveActivityToken token = liveActivityToken();
        when(repository.findByActiveTrue()).thenReturn(List.of(token));
        LiveActivityUpdateService service = new LiveActivityUpdateService(
                repository,
                new StubLiveActivityContentStateBuilder(),
                new InvalidTokenApnsPushService(),
                CLOCK
        );

        var result = service.deliverUpdate(game, event);

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(token.isActive()).isFalse();
    }

    private static final class StubLiveActivityContentStateBuilder extends LiveActivityContentStateBuilder {
        private StubLiveActivityContentStateBuilder() {
            super(null);
        }

        @Override
        public boolean matches(Game game, LiveActivityToken token) {
            return true;
        }

        @Override
        public Map<String, Object> build(Game game, LiveActivityToken token, NotificationEvent event) {
            return Map.of(
                    "isPreGame", false,
                    "favoriteScoreText", "1",
                    "opponentScoreText", "1"
            );
        }
    }

    private Game fixtureGame() {
        Team homeTeam = new Team(UUID.randomUUID(), "hanwha", "한화 이글스", "HAN", "Hanwha Eagles", null);
        Team awayTeam = new Team(UUID.randomUUID(), "lotte", "롯데 자이언츠", "LOT", "Lotte Giants", null);
        return new Game(
                UUID.randomUUID(),
                "20260605-LOT-HAN",
                "kbo",
                "20260605HHLT0",
                LocalDate.of(2026, 6, 5),
                OffsetDateTime.of(2026, 6, 5, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "대전",
                GameStatus.LIVE,
                homeTeam,
                awayTeam,
                1,
                1,
                "1회 초",
                false,
                false,
                null,
                null,
                null
        );
    }

    private LiveActivityToken liveActivityToken() {
        return new LiveActivityToken(
                UUID.randomUUID(),
                "activity-1",
                "ios",
                "sandbox",
                "activity-token",
                "install-1",
                "hanwha",
                "20260605-LOT-HAN",
                "20260605HHLT0",
                UUID.randomUUID().toString(),
                "provider:20260605HHLT0",
                OffsetDateTime.now(CLOCK)
        );
    }

    private static final class InvalidTokenApnsPushService extends ApnsPushService {
        private InvalidTokenApnsPushService() {
            super(new ApnsProperties(), CLOCK);
        }

        @Override
        public ApnsSendResult sendLiveActivityUpdate(NotificationEvent event, LiveActivityToken liveActivityToken, Map<String, Object> contentState) {
            return new ApnsSendResult(false, false, true, "Unregistered");
        }
    }
}
