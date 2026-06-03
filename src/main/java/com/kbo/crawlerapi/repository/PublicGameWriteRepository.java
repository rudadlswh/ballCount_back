package com.kbo.crawlerapi.repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboScheduleParser.ParsedScheduleGame;

@Repository
public class PublicGameWriteRepository implements ScheduleGameWriteRepository {

    private static final DateTimeFormatter GENERATED_PROVIDER_GAME_ID_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final JdbcTemplate jdbcTemplate;

    public PublicGameWriteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public GameWriteResult upsertScheduleGame(
            ParsedScheduleGame parsedGame,
            Team awayTeam,
            Team homeTeam,
            String publicGameId,
            OffsetDateTime sourceUpdatedAt
    ) {
        Optional<PublicGameRow> existingByProviderGameId = parsedGame.providerGameId() == null
                ? Optional.empty()
                : findByProviderAndProviderGameId(parsedGame.provider(), parsedGame.providerGameId());

        Optional<PublicGameRow> existingGame = existingByProviderGameId.isPresent()
                ? existingByProviderGameId
                : findByProviderAndNaturalKey(parsedGame, awayTeam, homeTeam);

        if (existingGame.isPresent()) {
            PublicGameRow existing = existingGame.get();
            String providerGameId = parsedGame.providerGameId() == null
                    ? existing.providerGameId()
                    : parsedGame.providerGameId();
            boolean changed = hasChanges(existing, parsedGame, awayTeam, homeTeam, publicGameId, providerGameId, sourceUpdatedAt);
            if (changed) {
                update(existing, parsedGame, awayTeam, homeTeam, publicGameId, providerGameId, sourceUpdatedAt);
            }
            return new GameWriteResult(false, changed);
        }

        insert(parsedGame, awayTeam, homeTeam, publicGameId, effectiveProviderGameId(parsedGame, awayTeam, homeTeam), sourceUpdatedAt);
        return new GameWriteResult(true, false);
    }

    private Optional<PublicGameRow> findByProviderAndProviderGameId(String provider, String providerGameId) {
        return jdbcTemplate.query(
                """
                        SELECT id, public_game_id, provider, provider_game_id, game_date, scheduled_at, stadium, status,
                               home_team_id, away_team_id, home_score, away_score, is_cancelled,
                               is_postponed, cancel_reason, raw_cancel_text, away_starting_pitcher_name,
                               home_starting_pitcher_name, source_updated_at
                        FROM games
                        WHERE provider = ? AND provider_game_id = ?
                        """,
                rowMapper(),
                provider,
                providerGameId
        ).stream().findFirst();
    }

    private Optional<PublicGameRow> findByProviderAndNaturalKey(
            ParsedScheduleGame parsedGame,
            Team awayTeam,
            Team homeTeam
    ) {
        return jdbcTemplate.query(
                """
                        SELECT id, public_game_id, provider, provider_game_id, game_date, scheduled_at, stadium, status,
                               home_team_id, away_team_id, home_score, away_score, is_cancelled,
                               is_postponed, cancel_reason, raw_cancel_text, away_starting_pitcher_name,
                               home_starting_pitcher_name, source_updated_at
                        FROM games
                        WHERE provider = ?
                          AND game_date = ?
                          AND home_team_id = ?
                          AND away_team_id = ?
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """,
                rowMapper(),
                parsedGame.provider(),
                parsedGame.gameDate(),
                homeTeam.getId(),
                awayTeam.getId()
        ).stream().findFirst();
    }

    private void insert(
            ParsedScheduleGame parsedGame,
            Team awayTeam,
            Team homeTeam,
            String publicGameId,
            String providerGameId,
            OffsetDateTime sourceUpdatedAt
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO games (
                            id, provider, provider_game_id, game_date, scheduled_at, stadium, status,
                            home_team_id, away_team_id, home_score, away_score, inning_state,
                            is_cancelled, is_postponed, cancel_reason, raw_cancel_text,
                            away_starting_pitcher_name, home_starting_pitcher_name, source_updated_at, public_game_id
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                parsedGame.provider(),
                providerGameId,
                parsedGame.gameDate(),
                parsedGame.scheduledAt(),
                parsedGame.stadium(),
                parsedGame.status().getApiValue(),
                homeTeam.getId(),
                awayTeam.getId(),
                normalizedScore(parsedGame.homeScore()),
                normalizedScore(parsedGame.awayScore()),
                parsedGame.isCancelled(),
                parsedGame.isPostponed(),
                cancelReasonValue(parsedGame.cancelReason()),
                parsedGame.rawCancelText(),
                normalizedText(parsedGame.awayStartingPitcherName()),
                normalizedText(parsedGame.homeStartingPitcherName()),
                sourceUpdatedAt,
                publicGameId
        );
    }

    private void update(
            PublicGameRow existing,
            ParsedScheduleGame parsedGame,
            Team awayTeam,
            Team homeTeam,
            String publicGameId,
            String providerGameId,
            OffsetDateTime sourceUpdatedAt
    ) {
        jdbcTemplate.update(
                """
                        UPDATE games
                        SET provider_game_id = ?,
                            public_game_id = ?,
                            game_date = ?,
                            scheduled_at = ?,
                            stadium = ?,
                            status = ?,
                            home_team_id = ?,
                            away_team_id = ?,
                            home_score = ?,
                            away_score = ?,
                            is_cancelled = ?,
                            is_postponed = ?,
                            cancel_reason = ?,
                            raw_cancel_text = ?,
                            away_starting_pitcher_name = COALESCE(?, away_starting_pitcher_name),
                            home_starting_pitcher_name = COALESCE(?, home_starting_pitcher_name),
                            source_updated_at = ?,
                            updated_at = now()
                        WHERE id = ?
                        """,
                providerGameId,
                publicGameId,
                parsedGame.gameDate(),
                parsedGame.scheduledAt(),
                parsedGame.stadium(),
                parsedGame.status().getApiValue(),
                homeTeam.getId(),
                awayTeam.getId(),
                effectiveHomeScore(existing, parsedGame),
                effectiveAwayScore(existing, parsedGame),
                parsedGame.isCancelled(),
                parsedGame.isPostponed(),
                cancelReasonValue(parsedGame.cancelReason()),
                parsedGame.rawCancelText(),
                normalizedText(parsedGame.awayStartingPitcherName()),
                normalizedText(parsedGame.homeStartingPitcherName()),
                sourceUpdatedAt,
                existing.id()
        );
    }

    private boolean hasChanges(
            PublicGameRow existing,
            ParsedScheduleGame parsedGame,
            Team awayTeam,
            Team homeTeam,
            String publicGameId,
            String providerGameId,
            OffsetDateTime sourceUpdatedAt
    ) {
        Integer effectiveHomeScore = effectiveHomeScore(existing, parsedGame);
        Integer effectiveAwayScore = effectiveAwayScore(existing, parsedGame);
        return !Objects.equals(existing.publicGameId(), publicGameId)
                || !Objects.equals(existing.providerGameId(), providerGameId)
                || !Objects.equals(existing.gameDate(), parsedGame.gameDate())
                || !sameInstant(existing.scheduledAt(), parsedGame.scheduledAt())
                || !Objects.equals(existing.stadium(), parsedGame.stadium())
                || !Objects.equals(existing.status(), parsedGame.status().getApiValue())
                || !Objects.equals(existing.homeTeamId(), homeTeam.getId())
                || !Objects.equals(existing.awayTeamId(), awayTeam.getId())
                || !Objects.equals(existing.homeScore(), effectiveHomeScore)
                || !Objects.equals(existing.awayScore(), effectiveAwayScore)
                || existing.cancelled() != parsedGame.isCancelled()
                || existing.postponed() != parsedGame.isPostponed()
                || existing.cancelReason() != parsedGame.cancelReason()
                || !Objects.equals(existing.rawCancelText(), parsedGame.rawCancelText())
                || incomingTextDiffers(existing.awayStartingPitcherName(), parsedGame.awayStartingPitcherName())
                || incomingTextDiffers(existing.homeStartingPitcherName(), parsedGame.homeStartingPitcherName())
                || !sameInstant(existing.sourceUpdatedAt(), sourceUpdatedAt);
    }

    private String cancelReasonValue(GameCancelReason cancelReason) {
        return cancelReason == null ? null : cancelReason.getApiValue();
    }

    private String effectiveProviderGameId(ParsedScheduleGame parsedGame, Team awayTeam, Team homeTeam) {
        if (parsedGame.providerGameId() != null) {
            return parsedGame.providerGameId();
        }
        OffsetDateTime scheduledAt = parsedGame.scheduledAt() == null
                ? parsedGame.gameDate().atStartOfDay().atOffset(ZoneOffset.UTC)
                : parsedGame.scheduledAt().withOffsetSameInstant(ZoneOffset.UTC);
        return "sched-%s-%s-%s".formatted(
                scheduledAt.format(GENERATED_PROVIDER_GAME_ID_TIME_FORMAT),
                awayTeam.getId().toString().substring(0, 8),
                homeTeam.getId().toString().substring(0, 8)
        );
    }

    private Integer normalizedScore(Integer score) {
        return score == null ? 0 : score;
    }

    private Integer effectiveHomeScore(PublicGameRow existing, ParsedScheduleGame parsedGame) {
        return shouldPreserveExistingScores(existing, parsedGame)
                ? existing.homeScore()
                : normalizedScore(parsedGame.homeScore());
    }

    private Integer effectiveAwayScore(PublicGameRow existing, ParsedScheduleGame parsedGame) {
        return shouldPreserveExistingScores(existing, parsedGame)
                ? existing.awayScore()
                : normalizedScore(parsedGame.awayScore());
    }

    private boolean shouldPreserveExistingScores(PublicGameRow existing, ParsedScheduleGame parsedGame) {
        GameStatus existingStatus = GameStatus.fromApiValue(existing.status());
        if (existingStatus == GameStatus.FINAL && parsedGame.status() == GameStatus.SUSPENDED) {
            return false;
        }
        if (!isScoreTrackedStatus(existingStatus) || !isScoreTrackedStatus(parsedGame.status())) {
            return false;
        }
        if (existing.homeScore() == null || existing.awayScore() == null) {
            return false;
        }
        if (parsedGame.homeScore() == null || parsedGame.awayScore() == null) {
            return true;
        }
        return parsedGame.homeScore() < existing.homeScore() || parsedGame.awayScore() < existing.awayScore();
    }

    private boolean isScoreTrackedStatus(GameStatus status) {
        return status == GameStatus.LIVE || status == GameStatus.SUSPENDED || status == GameStatus.FINAL;
    }

    private String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean incomingTextDiffers(String existingValue, String incomingValue) {
        String normalizedIncoming = normalizedText(incomingValue);
        return normalizedIncoming != null && !Objects.equals(normalizedText(existingValue), normalizedIncoming);
    }

    private boolean sameInstant(OffsetDateTime lhs, OffsetDateTime rhs) {
        if (lhs == null || rhs == null) {
            return lhs == rhs;
        }
        return lhs.toInstant().equals(rhs.toInstant());
    }

    private RowMapper<PublicGameRow> rowMapper() {
        return (rs, rowNum) -> new PublicGameRow(
                rs.getObject("id", UUID.class),
                rs.getString("public_game_id"),
                rs.getString("provider"),
                rs.getString("provider_game_id"),
                rs.getObject("game_date", java.time.LocalDate.class),
                rs.getObject("scheduled_at", OffsetDateTime.class),
                rs.getString("stadium"),
                rs.getString("status"),
                rs.getObject("home_team_id", UUID.class),
                rs.getObject("away_team_id", UUID.class),
                rs.getObject("home_score", Integer.class),
                rs.getObject("away_score", Integer.class),
                rs.getBoolean("is_cancelled"),
                rs.getBoolean("is_postponed"),
                cancelReason(rs.getString("cancel_reason")),
                rs.getString("raw_cancel_text"),
                rs.getString("away_starting_pitcher_name"),
                rs.getString("home_starting_pitcher_name"),
                rs.getObject("source_updated_at", OffsetDateTime.class)
        );
    }

    private GameCancelReason cancelReason(String value) {
        return value == null ? null : GameCancelReason.fromApiValue(value);
    }

    private record PublicGameRow(
            UUID id,
            String publicGameId,
            String provider,
            String providerGameId,
            java.time.LocalDate gameDate,
            OffsetDateTime scheduledAt,
            String stadium,
            String status,
            UUID homeTeamId,
            UUID awayTeamId,
            Integer homeScore,
            Integer awayScore,
            boolean cancelled,
            boolean postponed,
            GameCancelReason cancelReason,
            String rawCancelText,
            String awayStartingPitcherName,
            String homeStartingPitcherName,
            OffsetDateTime sourceUpdatedAt
    ) {
    }
}
