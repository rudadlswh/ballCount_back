package com.kbo.crawlerapi.parser;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;

@Component
public class KboScheduleParser {

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{2})\\.(\\d{2})\\([^)]+\\)");
    private static final Pattern GAME_ID_PATTERN = Pattern.compile("gameId=(\\d+\\w+)");
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+)\\s*vs\\s*(\\d+)");

    private final ObjectMapper objectMapper;

    public KboScheduleParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ParsedScheduleGame> parseMonthlySchedule(String responseBody, YearMonth yearMonth) {
        return parseMonthlyScheduleResult(responseBody, yearMonth).games();
    }

    public MonthlyScheduleParseResult parseMonthlyScheduleResult(String responseBody, YearMonth yearMonth) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            List<ParsedScheduleGame> games = new ArrayList<>();
            List<SkippedScheduleRow> skippedRows = new ArrayList<>();
            LocalDate currentDate = null;

            for (JsonNode rowNode : root.path("rows")) {
                JsonNode columns = rowNode.path("row");
                int index = 0;

                if (columns.isEmpty()) {
                    continue;
                }

                String firstCellText = columns.get(0).path("Text").asText("");
                LocalDate parsedDate = parseDate(firstCellText, yearMonth);
                if (parsedDate != null) {
                    currentDate = parsedDate;
                }

                if (currentDate == null) {
                    continue;
                }

                if (parsedDate != null) {
                    index = 1;
                }

                if (columns.size() < index + 8) {
                    continue;
                }

                String timeHtml = columns.get(index).path("Text").asText("");
                String playHtml = columns.get(index + 1).path("Text").asText("");
                String relayHtml = columns.get(index + 2).path("Text").asText("");
                String stadium = columns.get(index + 6).path("Text").asText("").trim();
                String note = columns.get(index + 7).path("Text").asText("").trim();

                ParseOutcome parseOutcome = parseGame(currentDate, timeHtml, playHtml, relayHtml, stadium, note);
                if (parseOutcome.parsedGame() != null) {
                    games.add(parseOutcome.parsedGame());
                }
                if (parseOutcome.skippedRow() != null) {
                    skippedRows.add(parseOutcome.skippedRow());
                }
            }

            return new MonthlyScheduleParseResult(games, skippedRows, root.path("rows").size());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse KBO schedule response", exception);
        }
    }

    private ParseOutcome parseGame(
            LocalDate gameDate,
            String timeHtml,
            String playHtml,
            String relayHtml,
            String stadium,
            String note
    ) {
        Document playDocument = Jsoup.parseBodyFragment(playHtml);
        List<Element> teamSpans = playDocument.select("span");
        String playText = playDocument.text().trim();
        if (teamSpans.size() < 2) {
            return new ParseOutcome(null, new SkippedScheduleRow(gameDate, "MALFORMED_PLAY_CELL", playText, null));
        }

        String awayProviderTeamName = teamSpans.get(0).text().trim();
        String homeProviderTeamName = teamSpans.get(teamSpans.size() - 1).text().trim();

        Integer awayScore = null;
        Integer homeScore = null;
        Matcher scoreMatcher = SCORE_PATTERN.matcher(playDocument.text());
        if (scoreMatcher.find()) {
            awayScore = Integer.parseInt(scoreMatcher.group(1));
            homeScore = Integer.parseInt(scoreMatcher.group(2));
        }

        Matcher gameIdMatcher = GAME_ID_PATTERN.matcher(relayHtml);
        String providerGameId = gameIdMatcher.find() ? gameIdMatcher.group(1) : null;
        OffsetDateTime scheduledAt = parseScheduledAt(gameDate, timeHtml);
        GameStatus status = resolveStatus(note, relayHtml, awayScore, homeScore);
        boolean isPostponed = status == GameStatus.POSTPONED;
        boolean isCancelled = status == GameStatus.CANCELLED;
        GameCancelReason cancelReason = resolveCancelReason(isCancelled, note);
        String rawCancelText = isCancelled ? normalizeRawCancelText(note) : null;

        ParsedScheduleGame parsedScheduleGame = new ParsedScheduleGame(
                "kbo",
                providerGameId,
                gameDate,
                scheduledAt,
                stadium.isBlank() ? null : stadium,
                status,
                isCancelled,
                isPostponed,
                awayProviderTeamName,
                homeProviderTeamName,
                awayScore,
                homeScore,
                cancelReason,
                rawCancelText,
                null
        );

        if (providerGameId == null) {
            return new ParseOutcome(parsedScheduleGame, new SkippedScheduleRow(
                    gameDate,
                    "MISSING_PROVIDER_GAME_ID",
                    playText,
                    note.isBlank() ? null : note
            ));
        }

        return new ParseOutcome(parsedScheduleGame, null);
    }

    private LocalDate parseDate(String cellText, YearMonth yearMonth) {
        String normalized = Jsoup.parse(cellText).text().trim();
        Matcher matcher = DATE_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }

        int month = Integer.parseInt(matcher.group(1));
        int day = Integer.parseInt(matcher.group(2));
        return LocalDate.of(yearMonth.getYear(), month, day);
    }

    private OffsetDateTime parseScheduledAt(LocalDate gameDate, String timeHtml) {
        String timeText = Jsoup.parse(timeHtml).text().trim();
        if (timeText.isBlank()) {
            return null;
        }

        String[] tokens = timeText.split(":");
        return gameDate.atTime(Integer.parseInt(tokens[0]), Integer.parseInt(tokens[1]))
                .atOffset(ZoneOffset.ofHours(9));
    }

    private GameStatus resolveStatus(String note, String relayHtml, Integer awayScore, Integer homeScore) {
        String relayText = Jsoup.parse(relayHtml).text().trim();
        if (note.contains("연기") || note.contains("순연")) {
            return GameStatus.POSTPONED;
        }
        String normalizedNote = note.toLowerCase(java.util.Locale.ROOT);
        if (note.contains("취소") || note.contains("노게임") || normalizedNote.contains("cancel") || normalizedNote.contains("no game") || normalizedNote.contains("nogame")) {
            return GameStatus.CANCELLED;
        }
        if (relayText.contains("프리뷰")) {
            return GameStatus.SCHEDULED;
        }
        if (relayText.contains("리뷰") || relayText.contains("하이라이트")) {
            return GameStatus.FINAL;
        }
        if (relayText.contains("문자중계") || relayText.contains("중계")) {
            return GameStatus.LIVE;
        }
        if (awayScore != null && homeScore != null) {
            return GameStatus.LIVE;
        }
        return GameStatus.SCHEDULED;
    }

    private GameCancelReason resolveCancelReason(boolean isCancelled, String note) {
        if (!isCancelled) {
            return null;
        }
        String rawCancelText = normalizeRawCancelText(note);
        if (rawCancelText == null) {
            return GameCancelReason.UNKNOWN;
        }
        if (rawCancelText.contains("우천")) {
            return GameCancelReason.RAIN;
        }
        if (rawCancelText.contains("그라운드")) {
            return GameCancelReason.GROUND;
        }
        if (isGenericCancelText(rawCancelText)) {
            return GameCancelReason.UNKNOWN;
        }
        return GameCancelReason.ETC;
    }

    private String normalizeRawCancelText(String note) {
        if (note == null) {
            return null;
        }
        String normalized = note.trim();
        if (normalized.isBlank() || "-".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    private boolean isGenericCancelText(String rawCancelText) {
        String collapsed = rawCancelText.replace(" ", "");
        return "취소".equals(collapsed) || "경기취소".equals(collapsed);
    }

    public record ParsedScheduleGame(
            String provider,
            String providerGameId,
            LocalDate gameDate,
            OffsetDateTime scheduledAt,
            String stadium,
            GameStatus status,
            boolean isCancelled,
            boolean isPostponed,
            String awayProviderTeamName,
            String homeProviderTeamName,
            Integer awayScore,
            Integer homeScore,
            GameCancelReason cancelReason,
            String rawCancelText,
            OffsetDateTime sourceUpdatedAt
    ) {
    }

    public record SkippedScheduleRow(
            LocalDate gameDate,
            String reason,
            String playText,
            String note
    ) {
    }

    public record MonthlyScheduleParseResult(
            List<ParsedScheduleGame> games,
            List<SkippedScheduleRow> skippedRows,
            int fetchedRowCount
    ) {
        public MonthlyScheduleParseResult(List<ParsedScheduleGame> games, List<SkippedScheduleRow> skippedRows) {
            this(games, skippedRows, games.size() + skippedRows.size());
        }

        public int skippedMissingProviderGameIdCount() {
            return (int) skippedRows.stream()
                    .filter(skippedRow -> "MISSING_PROVIDER_GAME_ID".equals(skippedRow.reason()))
                    .count();
        }
    }

    private record ParseOutcome(
            ParsedScheduleGame parsedGame,
            SkippedScheduleRow skippedRow
    ) {
    }
}
