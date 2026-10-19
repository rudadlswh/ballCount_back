package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.repository.GameEventReadRepository.GameEventRow;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScoringPlayDetailExtractor {

    private ScoringPlayDetailExtractor() {
    }

    private static final Logger log = LoggerFactory.getLogger(ScoringPlayDetailExtractor.class);
    private static final Pattern RUNS_SCORED_PATTERN = Pattern.compile("(\\d+)\\s*득점");
    private static final Pattern SCORE_PATTERN = Pattern.compile("(?<!\\d)(\\d+)\\s*[-:]\\s*(\\d+)(?!\\d)");
    private static final int RUN_SEQUENCE_WINDOW = 8;

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

        int runScoredEventCount = runScoredEventCount(events);
        ScoringCause cause = selectScoringCause(events, context).orElse(null);
        if (cause == null || !isReliable(cause.event(), cause.parsed(), context)) {
            return Optional.empty();
        }

        GameEventRow source = cause.event();
        ParsedPlay parsed = cause.parsed();
        log.info(
                "[ScoringFormatter] selectedEvent validated eventType={} player={} team={} inning={} half={}",
                source.eventType(),
                parsed.batterName(),
                context.battingTeamId(),
                source.inning() == null ? context.inning() : source.inning(),
                clean(source.inningHalf()) == null ? normalizeHalf(context.inningHalf()) : normalizeHalf(source.inningHalf())
        );
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
                context.homeTeamName(),
                source.eventType(),
                trimOnly(source.eventText()),
                runScoredEventCount
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

    private static boolean isReliable(GameEventRow event, ParsedPlay parsed, ScoringPlayContext context) {
        if (!context.battingTeamMatchesInningHalf()) {
            return false;
        }
        if (!sameInningHalf(event, context.inning(), context.inningHalf())) {
            return false;
        }
        if (playerLooksLikeDifferentTeam(parsed, context)) {
            log.info(
                    "[ScoringFormatter] rejected selectedEvent reason=team_mismatch eventPlayer={} eventTeam={} scoringTeam={} inning={} half={}",
                    parsed.batterName(),
                    "unknown",
                    context.battingTeamId(),
                    context.inning(),
                    normalizeHalf(context.inningHalf())
            );
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

    private static boolean playerLooksLikeDifferentTeam(ParsedPlay parsed, ScoringPlayContext context) {
        String eventPlayer = clean(parsed.batterName());
        String expectedBatter = clean(context.previousBatterName());
        return eventPlayer != null && expectedBatter != null && !samePlayerName(eventPlayer, expectedBatter);
    }

    private static boolean samePlayerName(String left, String right) {
        String normalizedLeft = normalizePlayerName(left);
        String normalizedRight = normalizePlayerName(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private static String normalizePlayerName(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.replaceAll("\\s+", "");
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

    private static Optional<ScoringCause> selectScoringCause(List<GameEventRow> events, ScoringPlayContext context) {
        SequenceRange runRange = runScoredSequenceRange(events);
        return events.stream()
                .filter(event -> !isRunScoredEvent(event))
                .filter(event -> inRunSequenceWindow(event, runRange))
                .map(event -> parse(event.eventText())
                        .map(parsed -> new ScoringCause(event, parsed, causePriority(event, parsed), distanceFromRunEvents(event, runRange)))
                        .orElse(null))
                .filter(cause -> cause != null && cause.priority() != null && cause.parsed().directScoringCandidate())
                .filter(cause -> isReliable(cause.event(), cause.parsed(), context))
                .min(Comparator
                        .comparingInt((ScoringCause cause) -> cause.priority())
                        .thenComparingInt(ScoringCause::runDistance)
                        .thenComparing((ScoringCause cause) -> cause.event().sequenceNumber(), Comparator.reverseOrder()));
    }

    public static int runScoredEventCount(List<GameEventRow> events) {
        if (events == null) {
            return 0;
        }
        return (int) events.stream().filter(ScoringPlayDetailExtractor::isRunScoredEvent).count();
    }

    private static SequenceRange runScoredSequenceRange(List<GameEventRow> events) {
        Integer min = null;
        Integer max = null;
        for (GameEventRow event : events) {
            if (!isRunScoredEvent(event)) {
                continue;
            }
            int sequence = event.sequenceNumber();
            min = min == null ? sequence : Math.min(min, sequence);
            max = max == null ? sequence : Math.max(max, sequence);
        }
        return min == null ? null : new SequenceRange(min, max);
    }

    private static boolean inRunSequenceWindow(GameEventRow event, SequenceRange runRange) {
        if (runRange == null) {
            return true;
        }
        int sequence = event.sequenceNumber();
        return sequence >= runRange.minSequence() - RUN_SEQUENCE_WINDOW
                && sequence <= runRange.maxSequence() + RUN_SEQUENCE_WINDOW;
    }

    private static int distanceFromRunEvents(GameEventRow event, SequenceRange runRange) {
        if (runRange == null) {
            return 0;
        }
        int sequence = event.sequenceNumber();
        if (sequence < runRange.minSequence()) {
            return runRange.minSequence() - sequence;
        }
        if (sequence > runRange.maxSequence()) {
            return sequence - runRange.maxSequence();
        }
        return 0;
    }

    private static Integer causePriority(GameEventRow event, ParsedPlay parsed) {
        String eventType = normalizeEventType(event.eventType());
        String result = parsed.resultText();
        if ("HOME_RUN".equals(eventType) || "홈런".equals(result)) {
            return 0;
        }
        if ("HIT".equals(eventType) || "안타".equals(result) || "2루타".equals(result) || "3루타".equals(result)) {
            return 1;
        }
        if ("ERROR".equals(eventType) || "상대 실책".equals(result)) {
            return 2;
        }
        if ("WALK".equals(eventType) || "밀어내기 볼넷".equals(result)) {
            return 3;
        }
        if ("HIT_BY_PITCH".equals(eventType) || "밀어내기 사구".equals(result)) {
            return 4;
        }
        if ("SACRIFICE".equals(eventType) || "희생플라이".equals(result)) {
            return 5;
        }
        if ("OUT".equals(eventType) || "DOUBLE_PLAY".equals(eventType) || "땅볼".equals(result)) {
            return 6;
        }
        return null;
    }

    private static boolean isRunScoredEvent(GameEventRow event) {
        if (event == null) {
            return false;
        }
        String eventType = normalizeEventType(event.eventType());
        String text = event.eventText();
        return "RUN_SCORED".equals(eventType)
                || (text != null && text.contains("홈인"));
    }

    private static String normalizeEventType(String eventType) {
        return eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean sameInningHalf(GameEventRow event, Integer inning, String inningHalf) {
        if (inning != null) {
            if (event.inning() == null || !inning.equals(event.inning())) {
                return false;
            }
        }
        String expectedHalf = normalizeHalf(inningHalf);
        String eventHalf = normalizeHalf(event.inningHalf());
        return expectedHalf == null || eventHalf != null && expectedHalf.equals(eventHalf);
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

    private static String trimOnly(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private record ScoringCause(
            GameEventRow event,
            ParsedPlay parsed,
            Integer priority,
            int runDistance
    ) {
    }

    private record SequenceRange(
            int minSequence,
            int maxSequence
    ) {
    }

    private record ScoreAfter(
            Integer awayScoreAfter,
            Integer homeScoreAfter
    ) {
    }
}
