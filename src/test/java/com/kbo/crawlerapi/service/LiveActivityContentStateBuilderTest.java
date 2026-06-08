package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiveActivityContentStateBuilderTest {

    @Mock
    private GameSnapshotRepository gameSnapshotRepository;

    @Test
    void zeroZeroScoreIsValidContentStateScore() {
        Game game = game(0, 0);
        LiveActivityContentStateBuilder builder = new LiveActivityContentStateBuilder(gameSnapshotRepository);
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())).thenReturn(Optional.empty());

        var state = builder.build(game, token("hanwha"));

        assertThat(state.get("favoriteScoreText")).isEqualTo("0");
        assertThat(state.get("opponentScoreText")).isEqualTo("0");
    }

    @Test
    void oneOneSnapshotScoreMapsIntoContentStateBeforeGameRowScore() {
        Game game = game(0, 0);
        GameSnapshot snapshot = snapshot(game, 1, 1);
        LiveActivityContentStateBuilder builder = new LiveActivityContentStateBuilder(gameSnapshotRepository);
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())).thenReturn(Optional.of(snapshot));

        var state = builder.build(game, token("hanwha"));

        assertThat(state.get("favoriteScoreText")).isEqualTo("1");
        assertThat(state.get("opponentScoreText")).isEqualTo("1");
        assertThat(state.get("inningText")).isEqualTo("1회 초");
        assertThat(state.get("currentBatterName")).isEqualTo("노시환");
        assertThat(state.get("currentPitcherName")).isEqualTo("박세웅");
    }

    @Test
    void englishSnapshotInningLabelMapsIntoKoreanContentState() {
        Game game = game(0, 0);
        GameSnapshot snapshot = snapshot(game, 1, 1, "Bottom 3");
        LiveActivityContentStateBuilder builder = new LiveActivityContentStateBuilder(gameSnapshotRepository);
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())).thenReturn(Optional.of(snapshot));

        var state = builder.build(game, token("hanwha"));

        assertThat(state.get("inningText")).isEqualTo("3회 말");
    }

    @Test
    void englishGameInningStateFallbackMapsIntoKoreanContentState() {
        Game game = game(0, 0, "Top 7");
        LiveActivityContentStateBuilder builder = new LiveActivityContentStateBuilder(gameSnapshotRepository);
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())).thenReturn(Optional.empty());

        var state = builder.build(game, token("hanwha"));

        assertThat(state.get("inningText")).isEqualTo("7회 초");
    }

    @Test
    void eventDerivedScoreMapsIntoContentStateWhenSnapshotScoreIsMissing() {
        Game game = game(0, 0);
        NotificationEvent event = new NotificationEvent(
                UUID.randomUUID(),
                game,
                NotificationEventService.EVENT_LEAD_CHANGED,
                "event-key",
                "title",
                "body",
                "{\"awayScore\":1,\"homeScore\":1}"
        );
        LiveActivityContentStateBuilder builder = new LiveActivityContentStateBuilder(gameSnapshotRepository);
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())).thenReturn(Optional.empty());

        var state = builder.build(game, token("hanwha"), event);

        assertThat(state.get("favoriteScoreText")).isEqualTo("1");
        assertThat(state.get("opponentScoreText")).isEqualTo("1");
    }

    @Test
    void matchesProviderPublicStableAndDatabaseIdentities() {
        Game game = game(1, 1);
        LiveActivityContentStateBuilder builder = new LiveActivityContentStateBuilder(gameSnapshotRepository);

        assertThat(builder.matches(game, tokenWithIdentity(null, "20260605HHLT0", null, null))).isTrue();
        assertThat(builder.matches(game, tokenWithIdentity("20260605-LOT-HAN", null, null, null))).isTrue();
        assertThat(builder.matches(game, tokenWithIdentity(null, null, game.getId().toString(), null))).isTrue();
        assertThat(builder.matches(game, tokenWithIdentity(null, null, null, "provider:20260605HHLT0"))).isTrue();
        assertThat(builder.matches(game, tokenWithIdentity(null, null, null, "public:20260605-lot-han"))).isTrue();
        assertThat(builder.matches(game, tokenWithIdentity(null, "other", null, null))).isFalse();
    }

    private Game game(Integer awayScore, Integer homeScore) {
        return game(awayScore, homeScore, "1회 초");
    }

    private Game game(Integer awayScore, Integer homeScore, String inningState) {
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
                homeScore,
                awayScore,
                inningState,
                false,
                false,
                null,
                null,
                "문동주",
                "박세웅",
                OffsetDateTime.now()
        );
    }

    private GameSnapshot snapshot(Game game, Integer awayScore, Integer homeScore) {
        return snapshot(game, awayScore, homeScore, "1회 초");
    }

    private GameSnapshot snapshot(Game game, Integer awayScore, Integer homeScore, String inningLabel) {
        return new GameSnapshot(
                UUID.randomUUID(),
                game,
                1,
                "top",
                inningLabel,
                1,
                2,
                0,
                true,
                false,
                false,
                "박세웅",
                "노시환",
                homeScore,
                awayScore,
                null,
                null,
                null,
                null,
                null,
                null,
                "hash",
                null,
                OffsetDateTime.now()
        );
    }

    private LiveActivityToken token(String favoriteTeamId) {
        return new LiveActivityToken(
                UUID.randomUUID(),
                "activity-1",
                "ios",
                "sandbox",
                "live-token",
                "install-1",
                favoriteTeamId,
                "20260605-LOT-HAN",
                "20260605HHLT0",
                UUID.randomUUID().toString(),
                "provider:20260605HHLT0",
                OffsetDateTime.now()
        );
    }

    private LiveActivityToken tokenWithIdentity(String publicGameId, String providerGameId, String databaseId, String stableIdentity) {
        return new LiveActivityToken(
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "ios",
                "sandbox",
                "live-token-" + UUID.randomUUID(),
                "install-1",
                "hanwha",
                publicGameId,
                providerGameId,
                databaseId,
                stableIdentity,
                OffsetDateTime.now()
        );
    }
}
