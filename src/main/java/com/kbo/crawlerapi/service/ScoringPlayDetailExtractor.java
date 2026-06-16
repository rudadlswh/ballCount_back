package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.repository.GameEventReadRepository.GameEventRow;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScoringPlayDetailExtractor {

    private ScoringPlayDetailExtractor() {
    }

    private static final Pattern RUNS_SCORED_PATTERN = Pattern.compile("(\\d+)\\s*득점");
    private static final Pattern SCORE_PATTERN = Pattern.compile("(?<!\\d)(\\d+)\\s*[-:]\\s*(\\d+)(?!\\d)");

    public static Optional<ScoringPlayDetail> extract(
            List<GameEventRow> recentEvents,
            ScoringPlayContext context
    ) {
        if (recentEvents == null || recentEvents.isEmpty() || context == null || context.scoreDelta() <= 0) {
            return Optional.empty();
        }

        List<GameEventRow> events = recentEvents.stream()
                .filter(event -> event != null && hasText(event.eventText()))
                .filter(event -> sameInningHalf(event, context.inning(), context.inningHalf()))
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

        if (parsed == null || parsed.resultText() == null || !isReliable(parsed, context)) {
            return Optional.empty();
        }

        GameEventRow source = runMarkerIndex == null ? events.get(events.size() - 1) : events.get(runMarkerIndex);
        return Optional.of(new ScoringPlayDetail(
                parsed.batterName(),
                parsed.resultText(),
                parsed.hitBaseCount(),
                context.scoreDelta(),
                context.scoreDelta(),
                source.inning() == null ? context.inning() : source.inning(),
                clean(source.inningHalf()) == null ? normalizeHalf(context.inningHalf()) : normalizeHalf(source.inningHalf()),
                context.battingTeamId(),
                context.battingTeamName(),
                context.awayScoreAfter(),
                context.homeScoreAfter(),
                context.awayTeamName(),
                context.homeTeamName()
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
        if (containsHomeRunText(normalized)) {
            return Optional.of(parsed(text, batterName, "홈런", null, true));
        }
        if (normalized.contains("3루타")) {
            return Optional.of(parsed(text, batterName, "3루타", 3, true));
        }
        if (normalized.contains("2루타")) {
            return Optional.of(parsed(text, batterName, "2루타", 2, true));
        }
        if (normalized.contains("1루타") || normalized.contains("안타")) {
            return Optional.of(parsed(text, batterName, "안타", 1, true));
        }
        if (normalized.contains("볼넷") || normalized.contains("4구")) {
            return Optional.of(parsed(text, batterName, "밀어내기 볼넷", null, true));
        }
        if (normalized.contains("사구") || normalized.contains("몸에맞")) {
            return Optional.of(parsed(text, batterName, "밀어내기 사구", null, true));
        }
        if (normalized.contains("희생플라이") || normalized.contains("희플")) {
            return Optional.of(parsed(text, batterName, "희생플라이", null, true));
        }
        if (normalized.contains("실책")) {
            return Optional.of(parsed(text, batterName, "상대 실책", null, true));
        }
        if (normalized.contains("폭투")) {
            return Optional.of(parsed(text, null, "폭투", null, true));
        }
        if (normalized.contains("포일")) {
            return Optional.of(parsed(text, null, "포일", null, true));
        }
        if (normalized.contains("보크")) {
            return Optional.of(parsed(text, null, "보크", null, true));
        }
        if (normalized.contains("땅볼")) {
            return Optional.of(parsed(text, batterName, "땅볼", null, true));
        }
        return Optional.empty();
    }

    private static ParsedPlay parsed(
            String text,
            String batterName,
            String resultText,
            Integer hitBaseCount,
            boolean directScoringCandidate
    ) {
        ScoreAfter scoreAfter = explicitScoreAfter(text);
        return new ParsedPlay(
                batterName,
                resultText,
                hitBaseCount,
                directScoringCandidate,
                explicitRunsScored(text),
                scoreAfter == null ? null : scoreAfter.awayScoreAfter(),
                scoreAfter == null ? null : scoreAfter.homeScoreAfter()
        );
    }

    private static boolean isReliable(ParsedPlay parsed, ScoringPlayContext context) {
        if (!context.battingTeamMatchesInningHalf()) {
            return false;
        }
        String previousBatter = clean(context.previousBatterName());
        String parsedBatter = clean(parsed.batterName());
        if (previousBatter != null) {
            if (parsedBatter == null || !previousBatter.equals(parsedBatter)) {
                return false;
            }
        } else if (parsedBatter != null) {
            return false;
        }
        if (parsed.explicitRunsScored() != null && parsed.explicitRunsScored() != context.scoreDelta()) {
            return false;
        }
        if (parsed.explicitAwayScoreAfter() != null && parsed.explicitHomeScoreAfter() != null
                && (!parsed.explicitAwayScoreAfter().equals(context.awayScoreAfter())
                || !parsed.explicitHomeScoreAfter().equals(context.homeScoreAfter()))) {
            return false;
        }
        if (!baseOccupancyAllowsRuns(parsed, context)) {
            return false;
        }
        return true;
    }

    private static boolean baseOccupancyAllowsRuns(ParsedPlay parsed, ScoringPlayContext context) {
        int occupiedBases = context.occupiedBaseCount();
        int runs = context.scoreDelta();
        if (runs <= 0) {
            return false;
        }
        if ("홈런".equals(parsed.resultText())) {
            return runs <= occupiedBases + 1;
        }
        if ("밀어내기 볼넷".equals(parsed.resultText()) || "밀어내기 사구".equals(parsed.resultText())) {
            return occupiedBases == 3 && runs == 1;
        }
        return runs <= occupiedBases;
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

    private static boolean containsHomeRunText(String normalized) {
        return normalized.contains("홈런")
                || normalized.contains("투런")
                || normalized.contains("쓰리런")
                || normalized.contains("만루홈런");
    }

    private static Integer explicitRunsScored(String text) {
        Matcher matcher = RUNS_SCORED_PATTERN.matcher(text);
        Integer latest = null;
        while (matcher.find()) {
            latest = Integer.parseInt(matcher.group(1));
        }
        return latest;
    }

    private static ScoreAfter explicitScoreAfter(String text) {
        Matcher matcher = SCORE_PATTERN.matcher(text);
        ScoreAfter scoreAfter = null;
        while (matcher.find()) {
            scoreAfter = new ScoreAfter(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
        return scoreAfter;
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

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    public record ScoringPlayContext(
            String previousBatterName,
            Integer inning,
            String inningHalf,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird,
            String battingTeamId,
            String battingTeamName,
            String awayTeamId,
            String homeTeamId,
            Integer awayScoreBefore,
            Integer homeScoreBefore,
            Integer awayScoreAfter,
            Integer homeScoreAfter,
            String awayTeamName,
            String homeTeamName
    ) {
        int scoreDelta() {
            return Math.max(0, nullSafe(awayScoreAfter) - nullSafe(awayScoreBefore))
                    + Math.max(0, nullSafe(homeScoreAfter) - nullSafe(homeScoreBefore));
        }

        int occupiedBaseCount() {
            return (runnerOnFirst ? 1 : 0) + (runnerOnSecond ? 1 : 0) + (runnerOnThird ? 1 : 0);
        }

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

    record ParsedPlay(
            String batterName,
            String resultText,
            Integer hitBaseCount,
            boolean directScoringCandidate,
            Integer explicitRunsScored,
            Integer explicitAwayScoreAfter,
            Integer explicitHomeScoreAfter
    ) {
    }

    private record ScoreAfter(
            Integer awayScoreAfter,
            Integer homeScoreAfter
    ) {
    }
}
