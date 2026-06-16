package com.kbo.crawlerapi.service;

import java.util.Optional;

public final class OnBaseNotificationFormatter {

    private OnBaseNotificationFormatter() {
    }

    public static Optional<ScoringPlayNotificationFormatter.NotificationText> text(OnBasePlayDetail detail) {
        if (!isFormattable(detail)) {
            return Optional.empty();
        }
        return Optional.of(new ScoringPlayNotificationFormatter.NotificationText(
                "%s 출루".formatted(detail.battingTeamName()),
                "%s %s · %s".formatted(inningText(detail.inning(), detail.inningHalf()), playText(detail), scoreText(detail))
        ));
    }

    private static String playText(OnBasePlayDetail detail) {
        String batter = detail.batterName().trim();
        if (hasText(detail.resultText())) {
            return "%s %s".formatted(batter, detail.resultText().trim());
        }
        return switch (nullSafe(detail.reachedBase())) {
            case 2 -> "%s 2루 도달".formatted(batter);
            case 3 -> "%s 3루 도달".formatted(batter);
            default -> "%s 출루".formatted(batter);
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

    private static String scoreText(OnBasePlayDetail detail) {
        if (detail.battingTeamId() != null && detail.battingTeamName().equals(detail.awayTeamName())) {
            return "%s %d-%d %s".formatted(
                    detail.awayTeamName(),
                    nullSafe(detail.awayScore()),
                    nullSafe(detail.homeScore()),
                    detail.homeTeamName()
            );
        }
        if (detail.battingTeamId() != null && detail.battingTeamName().equals(detail.homeTeamName())) {
            return "%s %d-%d %s".formatted(
                    detail.homeTeamName(),
                    nullSafe(detail.homeScore()),
                    nullSafe(detail.awayScore()),
                    detail.awayTeamName()
            );
        }
        return "%s %d-%d %s".formatted(
                detail.awayTeamName(),
                nullSafe(detail.awayScore()),
                nullSafe(detail.homeScore()),
                detail.homeTeamName()
        );
    }

    private static boolean isFormattable(OnBasePlayDetail detail) {
        return detail != null
                && hasText(detail.batterName())
                && detail.reachedBase() != null
                && detail.inning() != null
                && hasText(detail.inningHalf())
                && hasText(detail.battingTeamName())
                && detail.awayScore() != null
                && detail.homeScore() != null
                && hasText(detail.awayTeamName())
                && hasText(detail.homeTeamName());
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
}
