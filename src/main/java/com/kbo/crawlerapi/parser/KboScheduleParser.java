package com.kbo.crawlerapi.parser;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.parser.KboGameListParser.ParsedGameListGame;

@Component
public class KboScheduleParser {

    private static final Logger log = LoggerFactory.getLogger(KboScheduleParser.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{2})\\.(\\d{2})\\([^)]+\\)");
    private static final Pattern GAME_ID_PATTERN = Pattern.compile("gameId=(\\d+\\w+)");
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+)\\s*vs\\s*(\\d+)");
    private static final List<List<String>> LIKELY_ARRAY_PATHS = List.of(
            List.of("data", "games"),
            List.of("data", "rows"),
            List.of("data", "list"),
            List.of("games"),
            List.of("rows"),
            List.of("list"),
            List.of("schedule"),
            List.of("gameList")
    );
    private static final Map<String, String> PROVIDER_TEAM_NAME_BY_OFFICIAL_ID = Map.ofEntries(
            Map.entry("LG", "LG"),
            Map.entry("SK", "SSG"),
            Map.entry("SSG", "SSG"),
            Map.entry("HT", "KIA"),
            Map.entry("KIA", "KIA"),
            Map.entry("OB", "두산"),
            Map.entry("DOOSAN", "두산"),
            Map.entry("SS", "삼성"),
            Map.entry("SAM", "삼성"),
            Map.entry("LT", "롯데"),
            Map.entry("LOTTE", "롯데"),
            Map.entry("NC", "NC"),
            Map.entry("WO", "키움"),
            Map.entry("KIWOOM", "키움"),
            Map.entry("KT", "KT"),
            Map.entry("HH", "한화"),
            Map.entry("HANWHA", "한화")
    );

    private final ObjectMapper objectMapper;

    public KboScheduleParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ParsedScheduleGame> parseMonthlySchedule(String responseBody, YearMonth yearMonth) {
        return parseMonthlyScheduleResult(responseBody, yearMonth).games();
    }

    public MonthlyScheduleParseResult parseMonthlyScheduleResult(String responseBody, YearMonth yearMonth) {
        try {
            JsonNode root = normalizeRoot(objectMapper.readTree(responseBody));
            List<JsonNode> rowNodes = findScheduleRowArray(root);
            if (rowNodes.isEmpty()) {
                throw new IllegalStateException("No schedule row array found in KBO schedule response");
            }
            List<ParsedScheduleGame> games = new ArrayList<>();
            List<SkippedScheduleRow> skippedRows = new ArrayList<>();
            LocalDate currentDate = null;

            for (JsonNode rowNode : rowNodes) {
                try {
                    ParseOutcome parseOutcome = parseRow(rowNode, yearMonth, currentDate);
                    if (parseOutcome.currentDate() != null) {
                        currentDate = parseOutcome.currentDate();
                    }
                    if (parseOutcome.parsedGame() != null) {
                        games.add(parseOutcome.parsedGame());
                    }
                    if (parseOutcome.skippedRow() != null) {
                        skippedRows.add(parseOutcome.skippedRow());
                        logSkippedRow(parseOutcome.skippedRow());
                    }
                } catch (RuntimeException exception) {
                    skippedRows.add(new SkippedScheduleRow(currentDate, "ROW_PARSE_ERROR", rowPreview(rowNode), exception.getMessage()));
                    log.warn(
                            "Skipped malformed KBO schedule row. date={}, reason={}, rowPreview={}, error={}",
                            currentDate,
                            "ROW_PARSE_ERROR",
                            rowPreview(rowNode),
                            exception.getMessage()
                    );
                }
            }

            if (games.isEmpty()) {
                throw new IllegalStateException("No valid KBO schedule game rows parsed; skippedRowCount=" + skippedRows.size());
            }

            return new MonthlyScheduleParseResult(games, skippedRows, rowNodes.size());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse KBO schedule response", exception);
        }
    }

    private ParseOutcome parseRow(JsonNode rowNode, YearMonth yearMonth, LocalDate currentDate) {
        JsonNode columns = rowNode.has("row") ? rowNode.path("row") : rowNode;
        if (columns.isArray()) {
            return parseTableRow(columns, yearMonth, currentDate);
        }
        if (rowNode.isObject()) {
            return parseObjectRow(rowNode, yearMonth, currentDate);
        }
        return new ParseOutcome(null, new SkippedScheduleRow(currentDate, "UNSUPPORTED_ROW_SHAPE", rowPreview(rowNode), null), currentDate);
    }

    private ParseOutcome parseTableRow(JsonNode columns, YearMonth yearMonth, LocalDate currentDate) {
        if (columns.isEmpty()) {
            return new ParseOutcome(null, new SkippedScheduleRow(currentDate, "EMPTY_ROW", null, null), currentDate);
        }

        String firstCellText = cellText(columns.get(0));
        LocalDate parsedDate = parseDate(firstCellText, yearMonth);
        LocalDate effectiveDate = parsedDate == null ? currentDate : parsedDate;
        int index = parsedDate == null ? 0 : 1;

        if (effectiveDate == null) {
            return new ParseOutcome(null, new SkippedScheduleRow(null, "MISSING_DATE", rowPreview(columns), null), currentDate);
        }
        if (columns.size() < index + 8) {
            return new ParseOutcome(null, new SkippedScheduleRow(effectiveDate, "MALFORMED_ROW", rowPreview(columns), null), effectiveDate);
        }

        String timeHtml = cellText(columns.get(index));
        String playHtml = cellText(columns.get(index + 1));
        String relayHtml = cellText(columns.get(index + 2));
        String stadium = cellText(columns.get(index + 6)).trim();
        String note = cellText(columns.get(index + 7)).trim();

        ParseOutcome parsed = parseGame(effectiveDate, timeHtml, playHtml, relayHtml, stadium, note);
        return new ParseOutcome(parsed.parsedGame(), parsed.skippedRow(), effectiveDate);
    }

    private ParseOutcome parseObjectRow(JsonNode rowNode, YearMonth yearMonth, LocalDate currentDate) {
        LocalDate gameDate = parseObjectDate(rowNode, yearMonth);
        if (gameDate == null) {
            gameDate = currentDate;
        }
        if (gameDate == null) {
            return new ParseOutcome(null, new SkippedScheduleRow(null, "MISSING_DATE", rowPreview(rowNode), null), currentDate);
        }

        String awayProviderTeamName = teamName(rowNode, true);
        String homeProviderTeamName = teamName(rowNode, false);
        if (!hasText(awayProviderTeamName) || !hasText(homeProviderTeamName)) {
            return new ParseOutcome(null, new SkippedScheduleRow(gameDate, "MISSING_TEAM", rowPreview(rowNode), null), gameDate);
        }

        Integer awayScore = integerText(rowNode, "AWAY_SCORE", "AWAY_R", "T_SCORE", "awayScore");
        Integer homeScore = integerText(rowNode, "HOME_SCORE", "HOME_R", "B_SCORE", "homeScore");
        String providerGameId = firstText(rowNode, "G_ID", "GAME_ID", "gameId", "providerGameId");
        String stadium = firstText(rowNode, "S_NM", "STADIUM", "stadium", "stadiumName");
        String awayStartingPitcherName = startingPitcherName(rowNode, true);
        String homeStartingPitcherName = startingPitcherName(rowNode, false);
        String cancelText = normalizeRawCancelText(firstText(rowNode, "CANCEL_SC_NM", "CANCEL_NM", "cancelText", "cancelReason", "NOTE", "note"));
        String rawStatusText = firstText(rowNode, "GAME_STATE_SC_NM", "GAME_STATE_NM", "GAME_SC_NM", "STATUS_NM", "GAME_STATUS_NM", "status", "statusName");
        String statusText = String.join(
                " ",
                clean(cancelText),
                clean(rawStatusText)
        ).trim();
        GameStatus status = resolveStatus(statusText, "", awayScore, homeScore);
        boolean isPostponed = status == GameStatus.POSTPONED;
        boolean isCancelled = status == GameStatus.CANCELLED;
        String rawCancelText = isCancelled || isPostponed ? firstNonBlank(cancelText, normalizeRawCancelText(rawStatusText)) : null;
        String statusReason = isDelayedOrSuspended(status) ? firstNonBlank(clean(rawStatusText), cancelText) : null;
        GameCancelReason cancelReason = resolveCancelReason(isCancelled, rawCancelText);

        ParsedScheduleGame parsedScheduleGame = new ParsedScheduleGame(
                "kbo",
                providerGameId,
                gameDate,
                parseScheduledAt(gameDate, firstText(rowNode, "G_TM", "GAME_TIME", "START_TIME", "time", "PLAY_TM")),
                hasText(stadium) ? stadium.trim() : null,
                status,
                isCancelled,
                isPostponed,
                awayProviderTeamName,
                homeProviderTeamName,
                awayScore,
                homeScore,
                cancelReason,
                rawCancelText,
                awayStartingPitcherName,
                homeStartingPitcherName,
                statusReason,
                null
        );

        if (!hasText(providerGameId)) {
            return new ParseOutcome(parsedScheduleGame, new SkippedScheduleRow(
                    gameDate,
                    "MISSING_PROVIDER_GAME_ID",
                    "%s vs %s".formatted(awayProviderTeamName, homeProviderTeamName),
                    rawCancelText
            ), gameDate);
        }
        return new ParseOutcome(parsedScheduleGame, null, gameDate);
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
            return new ParseOutcome(null, new SkippedScheduleRow(gameDate, "MALFORMED_PLAY_CELL", playText, null), gameDate);
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
        String statusReason = isDelayedOrSuspended(status) ? normalizeRawCancelText(note) : null;

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
                null,
                null,
                statusReason,
                null
        );

        if (providerGameId == null) {
            return new ParseOutcome(parsedScheduleGame, new SkippedScheduleRow(
                    gameDate,
                    "MISSING_PROVIDER_GAME_ID",
                    playText,
                    note.isBlank() ? null : note
            ), gameDate);
        }

        return new ParseOutcome(parsedScheduleGame, null, gameDate);
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
        if (timeHtml == null) {
            return null;
        }
        String timeText = Jsoup.parse(timeHtml).text().trim();
        if (timeText.isBlank()) {
            return null;
        }
        if (timeText.length() >= 5 && timeText.charAt(2) == ':') {
            timeText = timeText.substring(0, 5);
        }

        String[] tokens = timeText.split(":");
        if (tokens.length < 2) {
            return null;
        }
        return gameDate.atTime(Integer.parseInt(tokens[0]), Integer.parseInt(tokens[1]))
                .atOffset(ZoneOffset.ofHours(9));
    }

    private GameStatus resolveStatus(String note, String relayHtml, Integer awayScore, Integer homeScore) {
        String relayText = Jsoup.parse(relayHtml).text().trim();
        String normalizedNote = normalizeStatusText(note);
        if (note.contains("연기") || note.contains("순연") || normalizedNote.contains("postponed")) {
            return GameStatus.POSTPONED;
        }
        if (isSuspensionText(note)) {
            return GameStatus.SUSPENDED;
        }
        if (isDelayText(note)) {
            return GameStatus.DELAYED;
        }
        if (note.contains("우천취소")
                || note.contains("경기취소")
                || note.contains("취소")
                || note.contains("노게임")
                || normalizedNote.equals("rain")
                || normalizedNote.contains("cancel")
                || normalizedNote.contains("no game")
                || normalizedNote.contains("nogame")) {
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
        if (hasNonZeroScore(awayScore, homeScore)) {
            return GameStatus.LIVE;
        }
        return GameStatus.SCHEDULED;
    }

    private boolean hasNonZeroScore(Integer awayScore, Integer homeScore) {
        return awayScore != null && homeScore != null && (awayScore > 0 || homeScore > 0);
    }

    private GameCancelReason resolveCancelReason(boolean isCancelled, String note) {
        if (!isCancelled) {
            return null;
        }
        String rawCancelText = normalizeRawCancelText(note);
        if (rawCancelText == null) {
            return GameCancelReason.UNKNOWN;
        }
        String normalized = normalizeStatusText(rawCancelText);
        if (rawCancelText.contains("우천") || normalized.contains("rain")) {
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

    private boolean isDelayText(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("우천지연")
                || value.contains("우천 지연")
                || value.contains("경기지연")
                || value.contains("경기 지연")
                || value.contains("개시지연")
                || value.contains("개시 지연")
                || value.contains("지연")
                || lower.contains("delay")
                || lower.contains("delayed")
                || lower.contains("rain delay");
    }

    private boolean isSuspensionText(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("서스펜")
                || value.contains("우천중단")
                || value.contains("우천 중단")
                || value.contains("강우중단")
                || value.contains("강우 중단")
                || value.contains("경기중단")
                || value.contains("경기 중단")
                || value.contains("일시중단")
                || value.contains("일시 중단")
                || value.contains("중단")
                || lower.contains("suspend")
                || lower.contains("suspended")
                || lower.contains("interrupted");
    }

    private boolean isDelayedOrSuspended(GameStatus status) {
        return status == GameStatus.DELAYED || status == GameStatus.SUSPENDED;
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

    private void logSkippedRow(SkippedScheduleRow skippedRow) {
        if ("MISSING_PROVIDER_GAME_ID".equals(skippedRow.reason())) {
            log.debug(
                    "Skipped KBO schedule row without provider game id. date={}, playText={}, note={}",
                    skippedRow.gameDate(),
                    skippedRow.playText(),
                    skippedRow.note()
            );
            return;
        }
        log.warn(
                "Skipped malformed KBO schedule row. date={}, reason={}, playText={}, note={}",
                skippedRow.gameDate(),
                skippedRow.reason(),
                skippedRow.playText(),
                skippedRow.note()
        );
    }

    private JsonNode normalizeRoot(JsonNode root) throws IOException {
        JsonNode d = root.path("d");
        if (d.isTextual()) {
            String text = d.asText();
            if (hasText(text) && (text.trim().startsWith("{") || text.trim().startsWith("["))) {
                return objectMapper.readTree(text);
            }
        }
        return root;
    }

    private List<JsonNode> findScheduleRowArray(JsonNode root) {
        if (root.isArray() && looksLikeScheduleRows(root)) {
            return arrayElements(root);
        }
        for (List<String> path : LIKELY_ARRAY_PATHS) {
            JsonNode node = nodeAt(root, path);
            if (node.isArray() && looksLikeScheduleRows(node)) {
                return arrayElements(node);
            }
        }
        JsonNode recursive = findFirstScheduleRowsArray(root);
        return recursive == null ? List.of() : arrayElements(recursive);
    }

    private JsonNode nodeAt(JsonNode root, List<String> path) {
        JsonNode current = root;
        for (String segment : path) {
            current = current.path(segment);
            if (current.isMissingNode() || current.isNull()) {
                return current;
            }
        }
        return current;
    }

    private JsonNode findFirstScheduleRowsArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray() && looksLikeScheduleRows(node)) {
            return node;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                JsonNode found = findFirstScheduleRowsArray(fields.next().getValue());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findFirstScheduleRowsArray(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean looksLikeScheduleRows(JsonNode arrayNode) {
        for (JsonNode element : arrayNode) {
            if (looksLikeScheduleRow(element)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeScheduleRow(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.has("row") && node.path("row").isArray()) {
            return true;
        }
        if (node.isArray()) {
            return node.size() >= 3;
        }
        return node.isObject()
                && (hasAny(node, "G_ID", "GAME_ID", "gameId", "providerGameId")
                || hasAny(node, "G_DT", "GAME_DATE", "GAME_DT", "gameDate")
                || hasAny(node, "AWAY_NM", "HOME_NM", "AWAY_ID", "HOME_ID")
                || hasAny(node, "CANCEL_SC_NM", "GAME_STATE_SC_NM", "STATUS_NM", "status"));
    }

    private boolean hasAny(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.has(key)) {
                return true;
            }
        }
        return false;
    }

    private List<JsonNode> arrayElements(JsonNode arrayNode) {
        List<JsonNode> elements = new ArrayList<>();
        arrayNode.forEach(elements::add);
        return elements;
    }

    private String cellText(JsonNode cell) {
        if (cell == null || cell.isNull()) {
            return "";
        }
        if (cell.has("Text")) {
            return cell.path("Text").asText("");
        }
        if (cell.isTextual() || cell.isNumber() || cell.isBoolean()) {
            return cell.asText("");
        }
        return cell.toString();
    }

    private LocalDate parseObjectDate(JsonNode rowNode, YearMonth yearMonth) {
        String value = firstText(rowNode, "G_DT", "GAME_DATE", "GAME_DT", "gameDate", "date", "GAME_DAY");
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        Matcher compact = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})").matcher(trimmed);
        if (compact.find()) {
            return LocalDate.of(
                    Integer.parseInt(compact.group(1)),
                    Integer.parseInt(compact.group(2)),
                    Integer.parseInt(compact.group(3))
            );
        }
        Matcher dashed = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(trimmed);
        if (dashed.find()) {
            return LocalDate.parse(dashed.group(0));
        }
        LocalDate tableDate = parseDate(trimmed, yearMonth);
        if (tableDate != null) {
            return tableDate;
        }
        return null;
    }

    private String teamName(JsonNode rowNode, boolean away) {
        String name = away
                ? firstText(rowNode, "AWAY_NM", "AWAY_TEAM_NM", "awayTeamName", "awayName", "away")
                : firstText(rowNode, "HOME_NM", "HOME_TEAM_NM", "homeTeamName", "homeName", "home");
        if (hasText(name)) {
            return name.trim();
        }
        String id = away
                ? firstText(rowNode, "AWAY_ID", "awayTeamId", "awayId")
                : firstText(rowNode, "HOME_ID", "homeTeamId", "homeId");
        if (!hasText(id)) {
            return null;
        }
        return PROVIDER_TEAM_NAME_BY_OFFICIAL_ID.getOrDefault(id.trim().toUpperCase(Locale.ROOT), id.trim());
    }

    private String startingPitcherName(JsonNode rowNode, boolean away) {
        String name = away
                ? firstText(
                        rowNode,
                        "T_PIT_P_NM",
                        "T_PIT_NM",
                        "AWAY_PIT_P_NM",
                        "AWAY_PIT_NM",
                        "AWAY_STARTING_PITCHER_NAME",
                        "AWAY_STARTER_NAME",
                        "awayStartingPitcherName",
                        "awayPitcherName",
                        "awayStarterName"
                )
                : firstText(
                        rowNode,
                        "B_PIT_P_NM",
                        "B_PIT_NM",
                        "HOME_PIT_P_NM",
                        "HOME_PIT_NM",
                        "HOME_STARTING_PITCHER_NAME",
                        "HOME_STARTER_NAME",
                        "homeStartingPitcherName",
                        "homePitcherName",
                        "homeStarterName"
                );
        return hasText(name) ? name.trim() : null;
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("").trim();
                if (!text.isBlank() && !isNormalCancelText(text)) {
                    return text;
                }
                if (!text.isBlank() && key.toLowerCase(Locale.ROOT).contains("cancel")) {
                    return text;
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : hasText(second) ? second : null;
    }

    private Integer integerText(JsonNode node, String... keys) {
        String text = firstText(node, keys);
        if (!hasText(text)) {
            return null;
        }
        Matcher matcher = Pattern.compile("-?\\d+").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group()) : null;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeStatusText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isNormalCancelText(String value) {
        String normalized = value == null ? "" : value.trim().replace(" ", "");
        return normalized.equals("정상경기") || normalized.equals("정상");
    }

    private String rowPreview(JsonNode rowNode) {
        if (rowNode == null) {
            return null;
        }
        String text = rowNode.toString();
        return text.length() <= 300 ? text : text.substring(0, 300);
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
            String awayStartingPitcherName,
            String homeStartingPitcherName,
            String statusReason,
            OffsetDateTime sourceUpdatedAt
    ) {
        public ParsedScheduleGame(
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
            this(
                    provider,
                    providerGameId,
                    gameDate,
                    scheduledAt,
                    stadium,
                    status,
                    isCancelled,
                    isPostponed,
                    awayProviderTeamName,
                    homeProviderTeamName,
                    awayScore,
                    homeScore,
                    cancelReason,
                    rawCancelText,
                    null,
                    null,
                    null,
                    sourceUpdatedAt
            );
        }

        public ParsedScheduleGame(
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
                String awayStartingPitcherName,
                String homeStartingPitcherName,
                OffsetDateTime sourceUpdatedAt
        ) {
            this(
                    provider,
                    providerGameId,
                    gameDate,
                    scheduledAt,
                    stadium,
                    status,
                    isCancelled,
                    isPostponed,
                    awayProviderTeamName,
                    homeProviderTeamName,
                    awayScore,
                    homeScore,
                    cancelReason,
                    rawCancelText,
                    awayStartingPitcherName,
                    homeStartingPitcherName,
                    null,
                    sourceUpdatedAt
            );
        }

        public ParsedScheduleGame withGameListStarterNames(ParsedGameListGame gameListGame) {
            String enrichedProviderGameId = hasText(providerGameId) ? providerGameId : gameListGame.providerGameId();
            String enrichedAwayStartingPitcherName = hasText(gameListGame.awayStartingPitcherName())
                    ? gameListGame.awayStartingPitcherName()
                    : awayStartingPitcherName;
            String enrichedHomeStartingPitcherName = hasText(gameListGame.homeStartingPitcherName())
                    ? gameListGame.homeStartingPitcherName()
                    : homeStartingPitcherName;
            GameStatus enrichedStatus = gameListGame.status() == null ? status : gameListGame.status();
            String enrichedStatusReason = hasText(gameListGame.statusReason()) ? gameListGame.statusReason() : statusReason;
            return new ParsedScheduleGame(
                    provider,
                    enrichedProviderGameId,
                    gameDate,
                    scheduledAt,
                    stadium,
                    enrichedStatus,
                    enrichedStatus == GameStatus.CANCELLED,
                    enrichedStatus == GameStatus.POSTPONED,
                    awayProviderTeamName,
                    homeProviderTeamName,
                    awayScore,
                    homeScore,
                    enrichedStatus == GameStatus.CANCELLED ? cancelReason : null,
                    enrichedStatus == GameStatus.CANCELLED || enrichedStatus == GameStatus.POSTPONED ? rawCancelText : null,
                    enrichedAwayStartingPitcherName,
                    enrichedHomeStartingPitcherName,
                    enrichedStatus == GameStatus.DELAYED || enrichedStatus == GameStatus.SUSPENDED ? enrichedStatusReason : null,
                    sourceUpdatedAt
            );
        }

        public ParsedScheduleGame withStatus(GameStatus status) {
            return new ParsedScheduleGame(
                    provider,
                    providerGameId,
                    gameDate,
                    scheduledAt,
                    stadium,
                    status,
                    status == GameStatus.CANCELLED,
                    status == GameStatus.POSTPONED,
                    awayProviderTeamName,
                    homeProviderTeamName,
                    awayScore,
                    homeScore,
                    status == GameStatus.CANCELLED ? cancelReason : null,
                    status == GameStatus.CANCELLED || status == GameStatus.POSTPONED ? rawCancelText : null,
                    awayStartingPitcherName,
                    homeStartingPitcherName,
                    status == GameStatus.DELAYED || status == GameStatus.SUSPENDED ? statusReason : null,
                    sourceUpdatedAt
            );
        }

        private boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
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
            SkippedScheduleRow skippedRow,
            LocalDate currentDate
    ) {
    }
}
