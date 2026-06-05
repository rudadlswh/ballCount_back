package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LiveActivityContentStateBuilder {

    private final GameSnapshotRepository gameSnapshotRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LiveActivityContentStateBuilder(GameSnapshotRepository gameSnapshotRepository) {
        this.gameSnapshotRepository = gameSnapshotRepository;
    }

    public Map<String, Object> build(Game game, LiveActivityToken token) {
        return build(game, token, null);
    }

    public Map<String, Object> build(Game game, LiveActivityToken token, NotificationEvent event) {
        GameSnapshot snapshot = gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId()).orElse(null);
        boolean preGame = game.getStatus() == GameStatus.SCHEDULED;
        String favoriteTeamId = token.getFavoriteTeamId();
        boolean favoriteIsAway = favoriteTeamId != null && favoriteTeamId.equals(game.getAwayTeam().getTeamCode());
        Map<String, Object> eventPayload = eventPayload(event);
        Integer homeScore = firstNonNull(snapshot == null ? null : snapshot.getHomeScore(), integerValue(eventPayload.get("homeScore")), game.getHomeScore());
        Integer awayScore = firstNonNull(snapshot == null ? null : snapshot.getAwayScore(), integerValue(eventPayload.get("awayScore")), game.getAwayScore());

        Map<String, Object> contentState = new LinkedHashMap<>();
        contentState.put("isPreGame", preGame);
        contentState.put("favoriteScoreText", preGame ? "-" : scoreText(favoriteIsAway ? awayScore : homeScore));
        contentState.put("opponentScoreText", preGame ? "-" : scoreText(favoriteIsAway ? homeScore : awayScore));
        contentState.put("inningText", preGame ? "" : inningText(game, snapshot));
        contentState.put("summaryText", preGame ? scheduledTimeText(game) : statusText(game.getStatus()));
        contentState.put("favoriteStartingPitcherName", favoriteIsAway ? clean(game.getAwayStartingPitcherName()) : clean(game.getHomeStartingPitcherName()));
        contentState.put("opponentStartingPitcherName", favoriteIsAway ? clean(game.getHomeStartingPitcherName()) : clean(game.getAwayStartingPitcherName()));
        contentState.put("balls", preGame || snapshot == null ? null : snapshot.getBalls());
        contentState.put("strikes", preGame || snapshot == null ? null : snapshot.getStrikes());
        contentState.put("outs", preGame || snapshot == null ? null : snapshot.getOuts());
        contentState.put("runnerOnFirst", preGame || snapshot == null ? null : snapshot.isRunnerOnFirst());
        contentState.put("runnerOnSecond", preGame || snapshot == null ? null : snapshot.isRunnerOnSecond());
        contentState.put("runnerOnThird", preGame || snapshot == null ? null : snapshot.isRunnerOnThird());
        contentState.put("currentBatterName", preGame || snapshot == null ? null : clean(snapshot.getCurrentBatterName()));
        contentState.put("currentPitcherName", preGame || snapshot == null ? null : clean(snapshot.getCurrentPitcherName()));
        return contentState;
    }

    public boolean matches(Game game, LiveActivityToken token) {
        return normalizedEquals(token.getPublicGameId(), game.getPublicGameId())
                || normalizedEquals(token.getProviderGameId(), game.getProviderGameId())
                || normalizedEquals(token.getDatabaseId(), game.getId().toString())
                || normalizedEquals(token.getStableDetailIdentity(), stableProviderIdentity(game))
                || normalizedEquals(token.getStableDetailIdentity(), stablePublicIdentity(game));
    }

    private String inningText(Game game, GameSnapshot snapshot) {
        if (snapshot != null && clean(snapshot.getInningLabel()) != null) {
            return snapshot.getInningLabel();
        }
        if (clean(game.getInningState()) != null) {
            return game.getInningState();
        }
        return statusText(game.getStatus());
    }

    private String scheduledTimeText(Game game) {
        if (game.getScheduledAt() == null) {
            return "";
        }
        return game.getScheduledAt().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String statusText(GameStatus status) {
        return switch (status) {
            case SCHEDULED -> "예정";
            case LIVE -> "LIVE";
            case FINAL -> "종료";
            case POSTPONED -> "연기";
            case CANCELLED -> "취소";
            case SUSPENDED -> "우천 중단";
            case UNKNOWN -> "상태 미확인";
        };
    }

    private String scoreText(Integer value) {
        return value == null ? "-" : value.toString();
    }

    private Integer firstNonNull(Integer first, Integer second, Integer third) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third;
    }

    private Map<String, Object> eventPayload(NotificationEvent event) {
        if (event == null || event.getPayload() == null || event.getPayload().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(event.getPayload(), new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Integer integerValue(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean normalizedEquals(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String stableProviderIdentity(Game game) {
        return clean(game.getProviderGameId()) == null ? null : "provider:" + game.getProviderGameId();
    }

    private String stablePublicIdentity(Game game) {
        return clean(game.getPublicGameId()) == null ? null : "public:" + game.getPublicGameId().toLowerCase(java.util.Locale.ROOT);
    }
}
