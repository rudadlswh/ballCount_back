package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.repository.GameEventReadRepository.GameEventRow;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class OnBasePlayDetailExtractor {

    private OnBasePlayDetailExtractor() {
    }

    public static Optional<OnBasePlayDetail> extract(List<GameEventRow> recentEvents, OnBasePlayContext context) {
        if (recentEvents == null || recentEvents.isEmpty() || context == null || !hasText(context.previousBatterName())) {
            return Optional.empty();
        }
        return recentEvents.stream()
                .filter(event -> event != null && hasText(event.eventText()))
                .filter(event -> sameInningHalf(event, context.inning(), context.inningHalf()))
                .sorted(Comparator.comparingInt(GameEventRow::sequenceNumber).reversed())
                .map(event -> parse(event.eventText()).orElse(null))
                .filter(parsed -> parsed != null && isReliable(parsed, context))
                .findFirst()
                .map(parsed -> new OnBasePlayDetail(
                        context.previousBatterName(),
                        parsed.resultText(),
                        context.reachedBase(),
                        context.inning(),
                        normalizeHalf(context.inningHalf()),
                        context.battingTeamId(),
                        context.battingTeamName(),
                        context.awayScore(),
                        context.homeScore(),
                        context.awayTeamName(),
                        context.homeTeamName(),
                        "officialText"
                ));
    }

    static Optional<ParsedOnBasePlay> parse(String eventText) {
        String text = clean(eventText);
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.replace(" ", "");
        String batterName = batterName(text);
        if (normalized.contains("홈런")) {
            return Optional.empty();
        }
        if (normalized.contains("3루타")) {
            return Optional.of(new ParsedOnBasePlay(batterName, "3루타", 3));
        }
        if (normalized.contains("2루타")) {
            return Optional.of(new ParsedOnBasePlay(batterName, "2루타", 2));
        }
        if (normalized.contains("1루타") || normalized.contains("안타")) {
            return Optional.of(new ParsedOnBasePlay(batterName, "안타", 1));
        }
        if (normalized.contains("볼넷") || normalized.contains("4구")) {
            return Optional.of(new ParsedOnBasePlay(batterName, "볼넷", 1));
        }
        if (normalized.contains("사구") || normalized.contains("몸에맞")) {
            return Optional.of(new ParsedOnBasePlay(batterName, "사구", 1));
        }
        if (normalized.contains("실책")) {
            return Optional.of(new ParsedOnBasePlay(batterName, "상대 실책 출루", 1));
        }
        if (normalized.contains("야수선택")) {
            return Optional.of(new ParsedOnBasePlay(batterName, "야수선택 출루", 1));
        }
        return Optional.empty();
    }

    private static boolean isReliable(ParsedOnBasePlay parsed, OnBasePlayContext context) {
        return context.battingTeamMatchesInningHalf()
                && hasText(parsed.batterName())
                && clean(parsed.batterName()).equals(clean(context.previousBatterName()))
                && parsed.reachedBase() != null
                && parsed.reachedBase().equals(context.reachedBase());
    }

    private static boolean sameInningHalf(GameEventRow event, Integer inning, String inningHalf) {
        if (inning != null && event.inning() != null && !inning.equals(event.inning())) {
            return false;
        }
        String expectedHalf = normalizeHalf(inningHalf);
        String eventHalf = normalizeHalf(event.inningHalf());
        return expectedHalf == null || eventHalf == null || expectedHalf.equals(eventHalf);
    }

    private static String batterName(String text) {
        int colonIndex = text.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }
        String before = text.substring(0, colonIndex).trim();
        if (before.startsWith("투수 ") || before.contains("주자 ")) {
            return null;
        }
        return clean(before);
    }

    private static String normalizeHalf(String half) {
        String cleaned = clean(half);
        if (cleaned == null) {
            return null;
        }
        String normalized = cleaned.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("top") || normalized.equals("초")) {
            return "top";
        }
        if (normalized.startsWith("bot") || normalized.equals("말")) {
            return "bottom";
        }
        return normalized;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim().replaceAll("\\s+", " ");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record OnBasePlayContext(
            String previousBatterName,
            Integer reachedBase,
            Integer inning,
            String inningHalf,
            String battingTeamId,
            String battingTeamName,
            String awayTeamId,
            String homeTeamId,
            Integer awayScore,
            Integer homeScore,
            String awayTeamName,
            String homeTeamName
    ) {
        boolean battingTeamMatchesInningHalf() {
            String normalizedHalf = normalizeHalf(inningHalf);
            if ("top".equals(normalizedHalf)) {
                return hasText(battingTeamId) && hasText(awayTeamId) && battingTeamId.equalsIgnoreCase(awayTeamId);
            }
            if ("bottom".equals(normalizedHalf)) {
                return hasText(battingTeamId) && hasText(homeTeamId) && battingTeamId.equalsIgnoreCase(homeTeamId);
            }
            return true;
        }
    }

    record ParsedOnBasePlay(String batterName, String resultText, Integer reachedBase) {
    }
}
