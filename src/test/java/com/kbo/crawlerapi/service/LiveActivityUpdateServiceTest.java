package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    void snapshotBsoChangeTriggersLiveActivityUpdateWithoutNotificationEvent() {
        Game game = fixtureGame();
        LiveActivityToken token = liveActivityToken();
        token.markContentStateDelivered(hash("""
                {"balls":1,"strikes":1,"outs":0,"currentBatterName":"김타자"}
                """.trim()), """
                {"balls":1,"strikes":1,"outs":0,"currentBatterName":"김타자"}
                """.trim(), OffsetDateTime.now(CLOCK));
        when(repository.findByActiveTrue()).thenReturn(List.of(token));
        RecordingApnsPushService apnsPushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        LiveActivityUpdateService service = new LiveActivityUpdateService(
                repository,
                new StubLiveActivityContentStateBuilder(contentState(
                        2,
                        1,
                        0,
                        "김타자"
                )),
                apnsPushService,
                CLOCK
        );

        var result = service.deliverUpdate(game);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(apnsPushService.contentStates).hasSize(1);
        assertThat(apnsPushService.contentStates.get(0)).containsEntry("balls", 2);
        assertThat(token.getContentStateHash()).isNotBlank();
    }

    @Test
    void currentBatterChangeTriggersLiveActivityUpdateWithoutNotificationEvent() {
        Game game = fixtureGame();
        LiveActivityToken token = liveActivityToken();
        token.markContentStateDelivered(hash("""
                {"balls":0,"strikes":0,"outs":1,"currentBatterName":"이전타자"}
                """.trim()), """
                {"balls":0,"strikes":0,"outs":1,"currentBatterName":"이전타자"}
                """.trim(), OffsetDateTime.now(CLOCK));
        when(repository.findByActiveTrue()).thenReturn(List.of(token));
        RecordingApnsPushService apnsPushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        LiveActivityUpdateService service = new LiveActivityUpdateService(
                repository,
                new StubLiveActivityContentStateBuilder(contentState(
                        0,
                        0,
                        1,
                        "새타자"
                )),
                apnsPushService,
                CLOCK
        );

        var result = service.deliverUpdate(game);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(apnsPushService.contentStates).hasSize(1);
        assertThat(apnsPushService.contentStates.get(0)).containsEntry("currentBatterName", "새타자");
    }

    @Test
    void unchangedSnapshotDoesNotSendApns() {
        Game game = fixtureGame();
        Map<String, Object> contentState = contentState(1, 2, 0, "김타자");
        String contentStateJson = stableJson(contentState);
        LiveActivityToken token = liveActivityToken();
        token.markContentStateDelivered(hash(contentStateJson), contentStateJson, OffsetDateTime.now(CLOCK));
        when(repository.findByActiveTrue()).thenReturn(List.of(token));
        RecordingApnsPushService apnsPushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        LiveActivityUpdateService service = new LiveActivityUpdateService(
                repository,
                new StubLiveActivityContentStateBuilder(contentState),
                apnsPushService,
                CLOCK
        );

        var result = service.deliverUpdate(game);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(apnsPushService.contentStates).isEmpty();
    }

    @Test
    void invalidLiveActivityApnsTokenMarksTokenInactive() {
        Game game = fixtureGame();
        LiveActivityToken token = liveActivityToken();
        when(repository.findByActiveTrue()).thenReturn(List.of(token));
        LiveActivityUpdateService service = new LiveActivityUpdateService(
                repository,
                new StubLiveActivityContentStateBuilder(Map.of(
                        "isPreGame", false,
                        "favoriteScoreText", "1",
                        "opponentScoreText", "1"
                )),
                new InvalidTokenApnsPushService(),
                CLOCK
        );

        var result = service.deliverUpdate(game);

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(token.isActive()).isFalse();
    }

    private static final class StubLiveActivityContentStateBuilder extends LiveActivityContentStateBuilder {
        private final Map<String, Object> contentState;

        private StubLiveActivityContentStateBuilder(Map<String, Object> contentState) {
            super(null);
            this.contentState = contentState;
        }

        @Override
        public boolean matches(Game game, LiveActivityToken token) {
            return true;
        }

        @Override
        public Map<String, Object> build(Game game, LiveActivityToken token) {
            return contentState;
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
        public ApnsSendResult sendLiveActivityUpdate(Game game, LiveActivityToken liveActivityToken, Map<String, Object> contentState) {
            return new ApnsSendResult(false, false, true, "Unregistered");
        }
    }

    private static final class RecordingApnsPushService extends ApnsPushService {
        private final ApnsSendResult result;
        private final List<Map<String, Object>> contentStates = new ArrayList<>();

        private RecordingApnsPushService(ApnsSendResult result) {
            super(new ApnsProperties(), CLOCK);
            this.result = result;
        }

        @Override
        public ApnsSendResult sendLiveActivityUpdate(Game game, LiveActivityToken liveActivityToken, Map<String, Object> contentState) {
            contentStates.add(contentState);
            return result;
        }
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> contentState(int balls, int strikes, int outs, String currentBatterName) {
        Map<String, Object> contentState = new LinkedHashMap<>();
        contentState.put("balls", balls);
        contentState.put("strikes", strikes);
        contentState.put("outs", outs);
        contentState.put("currentBatterName", currentBatterName);
        return contentState;
    }

    private static String stableJson(Map<String, Object> contentState) {
        try {
            return new ObjectMapper().writeValueAsString(contentState);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
