package com.kbo.crawlerapi.service;

import java.util.Optional;

public final class ScoringPlayNotificationFormatter {

    private ScoringPlayNotificationFormatter() {
    }

    public static Optional<NotificationText> scoreChangeText(ScoringPlayDetail detail) {
        if (!isDetailed(detail)) {
            return Optional.empty();
        }
        return Optional.of(new NotificationText(
                "%s 득점".formatted(detail.battingTeamName()),
                body(detail)
        ));
    }

    public static Optional<NotificationText> leadChangeText(
            ScoringPlayDetail detail,
            Integer previousAwayScore,
            Integer previousHomeScore
    ) {
        if (!isDetailed(detail)) {
            return Optional.empty();
        }
        String title = leadChangeTitle(detail, previousAwayScore, previousHomeScore).orElse(null);
        if (title == null) {
            return Optional.empty();
        }
        return Optional.of(new NotificationText(title, body(detail)));
    }

    private static Optional<String> leadChangeTitle(
            ScoringPlayDetail detail,
            Integer previousAwayScore,
            Integer previousHomeScore
    ) {
        int away = nullSafe(detail.awayScoreAfter());
        int home = nullSafe(detail.homeScoreAfter());
        if (away == home) {
            return Optional.of("동점");
        }
        String currentLeaderTeamId = away > home ? awayTeamId(detail) : homeTeamId(detail);
        if (currentLeaderTeamId != null && currentLeaderTeamId.equals(detail.battingTeamId())) {
            return Optional.of("%s 역전".formatted(detail.battingTeamName()));
        }
        if (previousAwayScore != null && previousHomeScore != null) {
            int previousAway = nullSafe(previousAwayScore);
            int previousHome = nullSafe(previousHomeScore);
            if (previousAway == previousHome && currentLeaderTeamId != null && currentLeaderTeamId.equals(detail.battingTeamId())) {
                return Optional.of("%s 역전".formatted(detail.battingTeamName()));
            }
        }
        return Optional.empty();
    }

    private static String body(ScoringPlayDetail detail) {
        return "%s %s · %s".formatted(
                inningText(detail.inning(), detail.inningHalf()),
                playAndRunsText(detail),
                scoreText(detail)
        );
    }

    private static String playAndRunsText(ScoringPlayDetail detail) {
        if (hasText(detail.selectedEventText())) {
            String eventText = normalizedEventText(detail.selectedEventText());
            return eventText.matches(".*\\d+\\s*득점.*") ? eventText : "%s, %s".formatted(eventText, runsText(detail.runsScored()));
        }
        if (hasText(detail.batterName())) {
            return "%s, %s".formatted(playText(detail), runsText(detail.runsScored()));
        }
        return "%s %s".formatted(playText(detail), runsText(detail.runsScored()));
    }

    private static String playText(ScoringPlayDetail detail) {
        String result = resultLabel(detail);
        if (hasText(detail.batterName())) {
            return "%s %s".formatted(detail.batterName().trim(), result);
        }
        String suffix = "상대 실책".equals(result) ? "으로" : "로";
        return result + suffix;
    }

    private static String normalizedEventText(String value) {
        return value.trim().replaceFirst("\\s*:\\s*", " ").replaceAll("\\s+", " ");
    }

    private static String resultLabel(ScoringPlayDetail detail) {
        String result = detail.resultText().trim();
        if (!"홈런".equals(result)) {
            return result;
        }
        return switch (Math.max(1, nullSafe(detail.runsScored()))) {
            case 2 -> "투런 홈런";
            case 3 -> "쓰리런 홈런";
            case 4 -> "만루홈런";
            default -> "홈런";
        };
    }

    private static String inningText(Integer inning, String inningHalf) {
        String half = switch (normalizeHalf(inningHalf)) {
            case "top" -> "초";
            case "bottom" -> "말";
            default -> "";
        };
        return "%d회%s".formatted(inning, half);
    }

    private static String runsText(Integer runsScored) {
        return "%d득점".formatted(Math.max(1, nullSafe(runsScored)));
    }

    private static String scoreText(ScoringPlayDetail detail) {
        boolean awayScored = "away".equals(teamSide(detail));
        if (awayScored) {
            return "%s %d-%d %s".formatted(
                    detail.awayTeamName(),
                    nullSafe(detail.awayScoreAfter()),
                    nullSafe(detail.homeScoreAfter()),
                    detail.homeTeamName()
            );
        }
        if ("home".equals(teamSide(detail))) {
            return "%s %d-%d %s".formatted(
                    detail.homeTeamName(),
                    nullSafe(detail.homeScoreAfter()),
                    nullSafe(detail.awayScoreAfter()),
                    detail.awayTeamName()
            );
        }
        return "%s %d-%d %s".formatted(
                detail.awayTeamName(),
                nullSafe(detail.awayScoreAfter()),
                nullSafe(detail.homeScoreAfter()),
                detail.homeTeamName()
        );
    }

    private static boolean isDetailed(ScoringPlayDetail detail) {
        return detail != null
                && hasText(detail.battingTeamName())
                && hasText(detail.resultText())
                && detail.runsScored() != null
                && detail.runsScored() > 0
                && detail.inning() != null
                && hasText(detail.inningHalf())
                && detail.awayScoreAfter() != null
                && detail.homeScoreAfter() != null
                && hasText(detail.awayTeamName())
                && hasText(detail.homeTeamName());
    }

    private static String teamSide(ScoringPlayDetail detail) {
        if (detail.battingTeamName() == null) {
            return null;
        }
        if (detail.battingTeamName().equals(detail.awayTeamName())) {
            return "away";
        }
        if (detail.battingTeamName().equals(detail.homeTeamName())) {
            return "home";
        }
        return null;
    }

    private static String awayTeamId(ScoringPlayDetail detail) {
        return "away".equals(teamSide(detail)) ? detail.battingTeamId() : null;
    }

    private static String homeTeamId(ScoringPlayDetail detail) {
        return "home".equals(teamSide(detail)) ? detail.battingTeamId() : null;
    }

    private static String normalizeHalf(String half) {
        if (half == null || half.isBlank()) {
            return "";
        }
        String normalized = half.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("top") || normalized.equals("초")) {
            return "top";
        }
        if (normalized.startsWith("bot") || normalized.equals("말")) {
            return "bottom";
        }
        return normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    public record NotificationText(String title, String body) {
    }
}
