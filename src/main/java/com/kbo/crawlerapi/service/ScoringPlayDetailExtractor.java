package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.repository.GameEventReadRepository.GameEventRow;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ScoringPlayDetailExtractor {

    private ScoringPlayDetailExtractor() {
    }

    public static Optional<ScoringPlayDetail> extract(
            List<GameEventRow> recentEvents,
            Integer runsScored,
            Integer inning,
            String inningHalf,
            String battingTeamId,
            String battingTeamName,
            Integer awayScoreAfter,
            Integer homeScoreAfter,
            String awayTeamName,
            String homeTeamName
    ) {
        if (recentEvents == null || recentEvents.isEmpty() || runsScored == null || runsScored <= 0) {
            return Optional.empty();
        }

        List<GameEventRow> events = recentEvents.stream()
                .filter(event -> event != null && hasText(event.eventText()))
                .filter(event -> sameInningHalf(event, inning, inningHalf))
                .sorted(Comparator.comparingInt(GameEventRow::sequenceNumber))
                .toList();
        if (events.isEmpty()) {
            return Optional.empty();
        }

        Integer runMarkerIndex = latestRunMarkerIndex(events);
        ParsedPlay parsed = null;
        if (runMarkerIndex != null) {
            parsed = parse(events.get(runMarkerIndex).eventText()).orElse(null);
            if (parsed == null || parsed.resultText() == null) {
                int lowerBound = Math.max(0, runMarkerIndex - 8);
                for (int index = runMarkerIndex - 1; index >= lowerBound; index--) {
                    parsed = parse(events.get(index).eventText()).orElse(null);
                    if (parsed != null && parsed.resultText() != null) {
                        break;
                    }
                }
            }
        }

        if (parsed == null || parsed.resultText() == null) {
            for (int index = events.size() - 1; index >= 0; index--) {
                parsed = parse(events.get(index).eventText()).orElse(null);
                if (parsed != null && parsed.resultText() != null && parsed.directScoringCandidate()) {
                    break;
                }
            }
        }

        if (parsed == null || parsed.resultText() == null) {
            return Optional.empty();
        }

        GameEventRow source = runMarkerIndex == null ? events.get(events.size() - 1) : events.get(runMarkerIndex);
        return Optional.of(new ScoringPlayDetail(
                parsed.batterName(),
                parsed.resultText(),
                parsed.hitBaseCount(),
                runsScored,
                runsScored,
                source.inning() == null ? inning : source.inning(),
                clean(source.inningHalf()) == null ? normalizeHalf(inningHalf) : normalizeHalf(source.inningHalf()),
                battingTeamId,
                battingTeamName,
                awayScoreAfter,
                homeScoreAfter,
                awayTeamName,
                homeTeamName
        ));
    }

    static Optional<ParsedPlay> parse(String eventText) {
        String text = clean(eventText);
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.replace(" ", "");
        String batterName = batterName(text);

        if (normalized.contains("홈인") && !containsRunCause(normalized)) {
            return Optional.empty();
        }
        if (normalized.contains("홈런")) {
            return Optional.of(new ParsedPlay(batterName, "홈런", null, true));
        }
        if (normalized.contains("3루타")) {
            return Optional.of(new ParsedPlay(batterName, "3루타", 3, true));
        }
        if (normalized.contains("2루타")) {
            return Optional.of(new ParsedPlay(batterName, "2루타", 2, true));
        }
        if (normalized.contains("1루타") || normalized.contains("안타")) {
            return Optional.of(new ParsedPlay(batterName, "안타", 1, true));
        }
        if (normalized.contains("볼넷") || normalized.contains("4구")) {
            return Optional.of(new ParsedPlay(batterName, "밀어내기 볼넷", null, true));
        }
        if (normalized.contains("사구") || normalized.contains("몸에맞")) {
            return Optional.of(new ParsedPlay(batterName, "밀어내기 사구", null, true));
        }
        if (normalized.contains("희생플라이") || normalized.contains("희플")) {
            return Optional.of(new ParsedPlay(batterName, "희생플라이", null, true));
        }
        if (normalized.contains("실책")) {
            return Optional.of(new ParsedPlay(batterName, "상대 실책", null, true));
        }
        if (normalized.contains("폭투")) {
            return Optional.of(new ParsedPlay(null, "폭투", null, true));
        }
        if (normalized.contains("포일")) {
            return Optional.of(new ParsedPlay(null, "포일", null, true));
        }
        if (normalized.contains("보크")) {
            return Optional.of(new ParsedPlay(null, "보크", null, true));
        }
        if (normalized.contains("땅볼")) {
            return Optional.of(new ParsedPlay(batterName, "땅볼", null, true));
        }
        return Optional.empty();
    }

    private static Integer latestRunMarkerIndex(List<GameEventRow> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            String text = events.get(index).eventText();
            if (text != null && (text.contains("홈인") || text.contains("득점"))) {
                return index;
            }
        }
        return null;
    }

    private static boolean sameInningHalf(GameEventRow event, Integer inning, String inningHalf) {
        if (inning != null && event.inning() != null && !inning.equals(event.inning())) {
            return false;
        }
        String expectedHalf = normalizeHalf(inningHalf);
        String eventHalf = normalizeHalf(event.inningHalf());
        return expectedHalf == null || eventHalf == null || expectedHalf.equals(eventHalf);
    }

    private static boolean containsRunCause(String normalized) {
        return normalized.contains("실책")
                || normalized.contains("폭투")
                || normalized.contains("포일")
                || normalized.contains("보크");
    }

    private static String batterName(String text) {
        int colonIndex = text.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }
        String before = text.substring(0, colonIndex).trim();
        if (before.startsWith("투수 ")) {
            return null;
        }
        if (before.contains("주자 ")) {
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

    record ParsedPlay(
            String batterName,
            String resultText,
            Integer hitBaseCount,
            boolean directScoringCandidate
    ) {
    }
}
