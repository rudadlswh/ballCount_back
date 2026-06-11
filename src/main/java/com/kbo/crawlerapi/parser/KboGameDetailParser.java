package com.kbo.crawlerapi.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;

@Component
public class KboGameDetailParser {

    private static final Logger log = LoggerFactory.getLogger(KboGameDetailParser.class);

    private final ObjectMapper objectMapper;

    public KboGameDetailParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedLineupData parseLineupData(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode groups = root.path("arrHitter");
            if (!groups.isArray() || groups.size() < 2) {
                return ParsedLineupData.empty(hash(responseBody));
            }

            List<ParsedLineupPlayer> away = parseLineupGroup(groups.get(0));
            List<ParsedLineupPlayer> home = parseLineupGroup(groups.get(1));
            if (away.isEmpty() && home.isEmpty()) {
                return ParsedLineupData.empty(hash(responseBody));
            }
            return new ParsedLineupData(away, home, hash(responseBody));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse KBO lineup response", exception);
        }
    }

    private List<ParsedLineupPlayer> parseLineupGroup(JsonNode group) throws IOException {
        String orderTableRaw = text(group, "table1");
        if (orderTableRaw == null) {
            return List.of();
        }

        JsonNode rows = objectMapper.readTree(orderTableRaw).path("rows");
        if (!rows.isArray()) {
            return List.of();
        }

        List<ParsedLineupPlayer> players = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode cells = row.path("row");
            if (!cells.isArray() || cells.size() < 3) {
                continue;
            }
            String battingOrder = gridText(cells.get(0));
            String position = gridText(cells.get(1));
            String name = gridText(cells.get(2));
            if (name == null) {
                continue;
            }
            players.add(new ParsedLineupPlayer(battingOrder, position, name));
        }
        return players;
    }

    public List<ParsedGameDetail> parseGameList(String responseBody) {
        try {
            JsonNode root = normalizeRoot(objectMapper.readTree(responseBody));
            List<ParsedGameDetail> details = new ArrayList<>();

            for (JsonNode row : gameRows(root)) {
                String providerGameId = text(row, "G_ID");
                if (providerGameId == null) {
                    continue;
                }

                GameStatus status = resolveStatus(row);
                Integer awayScore = hasRealScore(row, status) ? integer(row, "T_SCORE_CN") : null;
                Integer homeScore = hasRealScore(row, status) ? integer(row, "B_SCORE_CN") : null;
                Integer inning = integer(row, "GAME_INN_NO");
                String inningHalf = resolveHalf(text(row, "GAME_TB_SC"));
                String inningLabel = inning != null && inningHalf != null
                        ? ("%s %d".formatted("top".equals(inningHalf) ? "Top" : "Bottom", inning))
                        : null;
                String rawCancelText = text(row, "CANCEL_SC_NM");
                String statusReason = resolveStatusReason(row, status, rawCancelText);
                GameCancelReason cancelReason = resolveCancelReason(status, rawCancelText);
                logStatusDiagnostics(row, providerGameId, status, statusReason, cancelReason, rawCancelText);
                String awayStartingPitcherName = text(row, "T_PIT_P_NM");
                String homeStartingPitcherName = text(row, "B_PIT_P_NM");
                String currentPitcherName = currentPitcherName(row, inningHalf);
                String currentBatterName = currentBatterName(row, inningHalf);
                logCurrentPlayerSourceState(row, providerGameId, inningHalf, currentPitcherName, currentBatterName);
                Integer firstBaseBattingOrder = baseBattingOrder(row, "B1");
                Integer secondBaseBattingOrder = baseBattingOrder(row, "B2");
                Integer thirdBaseBattingOrder = baseBattingOrder(row, "B3");
                String firstBaseRunnerName = runnerName(row, "B1");
                String secondBaseRunnerName = runnerName(row, "B2");
                String thirdBaseRunnerName = runnerName(row, "B3");
                String firstBaseRunnerId = runnerId(row, "B1");
                String secondBaseRunnerId = runnerId(row, "B2");
                String thirdBaseRunnerId = runnerId(row, "B3");
                log.debug(
                        "[BaseRunners] payload occupancy first={} second={} third={}",
                        firstBaseBattingOrder != null && firstBaseBattingOrder > 0,
                        secondBaseBattingOrder != null && secondBaseBattingOrder > 0,
                        thirdBaseBattingOrder != null && thirdBaseBattingOrder > 0
                );
                log.debug(
                        "[BaseRunners] payload names first={} second={} third={}",
                        displayName(firstBaseRunnerName),
                        displayName(secondBaseRunnerName),
                        displayName(thirdBaseRunnerName)
                );
                logRunnerRelatedKeysIfNeeded(row, providerGameId, firstBaseRunnerName, secondBaseRunnerName, thirdBaseRunnerName);
                boolean lineupAvailable = integer(row, "LINEUP_CK") != null && integer(row, "LINEUP_CK") > 0;

                details.add(new ParsedGameDetail(
                        providerGameId,
                        status,
                        status == GameStatus.CANCELLED,
                        status == GameStatus.POSTPONED,
                        cancelReason,
                        status == GameStatus.CANCELLED ? rawCancelText : null,
                        awayScore,
                        homeScore,
                        inning,
                        inningHalf,
                        inningLabel,
                        integer(row, "BALL_CN"),
                        integer(row, "STRIKE_CN"),
                        integer(row, "OUT_CN"),
                        firstBaseBattingOrder != null && firstBaseBattingOrder > 0,
                        secondBaseBattingOrder != null && secondBaseBattingOrder > 0,
                        thirdBaseBattingOrder != null && thirdBaseBattingOrder > 0,
                        firstBaseBattingOrder,
                        secondBaseBattingOrder,
                        thirdBaseBattingOrder,
                        firstBaseRunnerName,
                        secondBaseRunnerName,
                        thirdBaseRunnerName,
                        firstBaseRunnerId,
                        secondBaseRunnerId,
                        thirdBaseRunnerId,
                        currentPitcherName,
                        currentBatterName,
                        homeStartingPitcherName,
                        awayStartingPitcherName,
                        lineupAvailable,
                        statusReason,
                        null,
                        hash(row.toString())
                ));
            }

            return details;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse KBO game detail response", exception);
        }
    }

    private JsonNode normalizeRoot(JsonNode root) throws IOException {
        JsonNode d = root.path("d");
        if (d.isTextual()) {
            String text = d.asText();
            if (text != null && (text.trim().startsWith("{") || text.trim().startsWith("["))) {
                return objectMapper.readTree(text);
            }
        }
        return root;
    }

    private List<JsonNode> gameRows(JsonNode root) {
        for (List<String> path : List.of(
                List.of("game"),
                List.of("Game"),
                List.of("games"),
                List.of("rows"),
                List.of("list"),
                List.of("data", "game"),
                List.of("data", "games"),
                List.of("data", "rows"),
                List.of("data", "list")
        )) {
            JsonNode node = nodeAt(root, path);
            if (node.isArray()) {
                return arrayElements(node);
            }
        }
        if (root.isArray()) {
            return arrayElements(root);
        }
        return List.of();
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

    private List<JsonNode> arrayElements(JsonNode arrayNode) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : arrayNode) {
            rows.add(row);
        }
        return rows;
    }

    private String currentPitcherName(JsonNode row, String inningHalf) {
        if ("top".equals(inningHalf)) {
            return text(row, "B_P_NM");
        }
        if ("bottom".equals(inningHalf)) {
            return text(row, "T_P_NM");
        }
        return null;
    }

    private String currentBatterName(JsonNode row, String inningHalf) {
        if ("top".equals(inningHalf)) {
            return text(row, "T_P_NM");
        }
        if ("bottom".equals(inningHalf)) {
            return text(row, "B_P_NM");
        }
        return null;
    }

    public ParsedGameDetail applyOfficialRunnerNamesFromLineup(ParsedGameDetail detail, ParsedLineupData lineupData) {
        if (detail == null || lineupData == null || !lineupData.hasLineups()) {
            return detail;
        }

        List<ParsedLineupPlayer> battingLineup = battingLineupForHalf(detail.inningHalf(), lineupData);
        if (battingLineup.isEmpty()) {
            return detail;
        }

        String first = clean(detail.firstBaseRunnerName()) != null
                ? detail.firstBaseRunnerName()
                : runnerNameByBattingOrder(detail.firstBaseBattingOrder(), battingLineup);
        String second = clean(detail.secondBaseRunnerName()) != null
                ? detail.secondBaseRunnerName()
                : runnerNameByBattingOrder(detail.secondBaseBattingOrder(), battingLineup);
        String third = clean(detail.thirdBaseRunnerName()) != null
                ? detail.thirdBaseRunnerName()
                : runnerNameByBattingOrder(detail.thirdBaseBattingOrder(), battingLineup);

        if (equalsClean(detail.firstBaseRunnerName(), first)
                && equalsClean(detail.secondBaseRunnerName(), second)
                && equalsClean(detail.thirdBaseRunnerName(), third)) {
            return detail;
        }

        log.debug(
                "[BaseRunners] resolved names first={} second={} third={} source=order",
                displayName(first),
                displayName(second),
                displayName(third)
        );
        return detail.withBaseRunnerNames(first, second, third);
    }

    private List<ParsedLineupPlayer> battingLineupForHalf(String inningHalf, ParsedLineupData lineupData) {
        if ("top".equals(inningHalf)) {
            return lineupData.away();
        }
        if ("bottom".equals(inningHalf)) {
            return lineupData.home();
        }
        return List.of();
    }

    private String runnerNameByBattingOrder(Integer battingOrder, List<ParsedLineupPlayer> lineup) {
        if (battingOrder == null || battingOrder <= 0) {
            return null;
        }
        String orderText = Integer.toString(battingOrder);
        List<String> matches = lineup.stream()
                .filter(player -> orderText.equals(normalizeBattingOrder(player.battingOrder())))
                .map(ParsedLineupPlayer::name)
                .map(this::clean)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (matches.size() != 1) {
            return null;
        }
        return matches.get(0);
    }

    private String normalizeBattingOrder(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(cleaned);
        return matcher.find() ? matcher.group() : null;
    }

    private Integer baseBattingOrder(JsonNode row, String basePrefix) {
        String number = basePrefix.substring(1);
        return firstInteger(row,
                basePrefix + "_BAT_ORDER_NO",
                basePrefix + "BATORDERNO",
                "BASE" + number + "_BAT_ORDER_NO",
                "BASE" + number + "BATORDERNO"
        );
    }

    private String runnerName(JsonNode row, String basePrefix) {
        return firstText(row,
                basePrefix + "_RUNNER_NM",
                basePrefix + "_RUNNER_NAME",
                basePrefix + "_P_NM",
                basePrefix + "_P_NAME",
                basePrefix + "_PLAYER_NM",
                basePrefix + "_PLAYER_NAME",
                basePrefix + "_NM",
                basePrefix + "_NAME"
        );
    }

    private String runnerId(JsonNode row, String basePrefix) {
        return firstText(row,
                basePrefix + "_RUNNER_ID",
                basePrefix + "_P_ID",
                basePrefix + "_PLAYER_ID",
                basePrefix + "_PERSON_ID",
                basePrefix + "_PCODE",
                basePrefix + "_P_CODE"
        );
    }

    private String firstText(JsonNode row, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = text(row, fieldName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Integer firstInteger(JsonNode row, String... fieldNames) {
        for (String fieldName : fieldNames) {
            Integer value = integer(row, fieldName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String displayName(String value) {
        return value == null || value.isBlank() ? "<nil>" : value.trim();
    }

    private boolean equalsClean(String left, String right) {
        return java.util.Objects.equals(clean(left), clean(right));
    }

    private void logCurrentPlayerSourceState(
            JsonNode row,
            String providerGameId,
            String inningHalf,
            String currentPitcherName,
            String currentBatterName
    ) {
        if (!log.isDebugEnabled()) {
            return;
        }
        String awaySidePlayerName = text(row, "T_P_NM");
        String homeSidePlayerName = text(row, "B_P_NM");
        if (currentPitcherName == null || currentBatterName == null) {
            log.debug(
                    "current player source missing provider_game_id={} inning_half={} t_p_nm={} b_p_nm={} parsed_pitcher={} parsed_batter={}",
                    providerGameId,
                    inningHalf,
                    awaySidePlayerName,
                    homeSidePlayerName,
                    currentPitcherName,
                    currentBatterName
            );
            return;
        }
        log.debug(
                "current player source mapped provider_game_id={} inning_half={} t_p_nm={} b_p_nm={} parsed_pitcher={} parsed_batter={}",
                providerGameId,
                inningHalf,
                awaySidePlayerName,
                homeSidePlayerName,
                currentPitcherName,
                currentBatterName
        );
    }

    private GameStatus resolveStatus(JsonNode row) {
        String cancelName = text(row, "CANCEL_SC_NM");
        String rawStatusName = firstText(row, "GAME_STATE_SC_NM", "GAME_STATE_NM", "GAME_SC_NM", "STATUS_NM", "GAME_STATUS_NM");
        String statusText = joinClean(cancelName, rawStatusName);
        if (isPostponedText(statusText)) {
            return GameStatus.POSTPONED;
        }
        if (isCancelledText(statusText)) {
            return GameStatus.CANCELLED;
        }
        if (isSuspendedText(statusText)) {
            return GameStatus.SUSPENDED;
        }

        String gameState = text(row, "GAME_STATE_SC");
        if (hasReliableFinalMarker(row)) {
            return GameStatus.FINAL;
        }
        if ("2".equals(gameState)) {
            return GameStatus.LIVE;
        }
        if (integer(row, "GAME_INN_NO") != null) {
            return GameStatus.LIVE;
        }
        if ("1".equals(gameState)) {
            return GameStatus.SCHEDULED;
        }
        return GameStatus.UNKNOWN;
    }

    public Optional<ParsedScoreBoardStatus> parseScoreBoardStatus(String providerGameId, String responseBody) {
        if (isInvalidScoreBoardResponse(responseBody)) {
            log.info(
                    "[ScoreBoardStatus] providerGameId={} ignored=true reason=invalid-scoreboard-response rawStatusText={}",
                    providerGameId,
                    abbreviate(scoreBoardStatusText(responseBody), 500)
            );
            return Optional.empty();
        }
        String rawText = scoreBoardStatusText(responseBody);
        String reason = interruptionReason(rawText);
        if (reason == null) {
            log.info(
                    "[ScoreBoardStatus] providerGameId={} rawStatusText={} normalizedStatus={} statusReason={}",
                    providerGameId,
                    abbreviate(clean(rawText), 500),
                    null,
                    null
            );
            return Optional.empty();
        }
        log.info(
                "[ScoreBoardStatus] providerGameId={} rawStatusText={} normalizedStatus={} statusReason={}",
                providerGameId,
                abbreviate(clean(rawText), 500),
                GameStatus.SUSPENDED.getApiValue(),
                reason
        );
        return Optional.of(new ParsedScoreBoardStatus(GameStatus.SUSPENDED, reason));
    }

    private String scoreBoardStatusText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        String trimmed = responseBody.trim();
        List<String> values = new ArrayList<>();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                collectTextValues(objectMapper.readTree(trimmed), values);
            } catch (IOException ignored) {
                values.add(trimmed);
            }
        } else {
            values.add(Jsoup.parse(responseBody).text());
        }
        return joinClean(values.toArray(String[]::new));
    }

    private boolean isInvalidScoreBoardResponse(String responseBody) {
        String rawText = scoreBoardStatusText(responseBody);
        if (rawText == null) {
            return false;
        }
        String normalized = rawText.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("object moved")
                || normalized.contains("입력 문자열의 형식이 잘못되었습니다")
                || normalized.contains("input string was not in a correct format")
                || normalized.contains("이용에 불편을 드려 죄송합니다")
                || normalized.contains("error | kbo");
    }

    private void collectTextValues(JsonNode node, List<String> values) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String value = clean(node.asText());
            if (value != null) {
                values.add(value);
            }
            return;
        }
        if (node.isValueNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectTextValues(child, values));
            return;
        }
        Iterator<JsonNode> elements = node.elements();
        while (elements.hasNext()) {
            collectTextValues(elements.next(), values);
        }
    }

    private String interruptionReason(String value) {
        if (value == null) {
            return null;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String phrase : List.of("우천중단", "강우중단", "경기중단", "일시중단")) {
            if (value.contains(phrase)) {
                return phrase;
            }
        }
        if (lower.contains("rain delay")) {
            return "rain delay";
        }
        if (lower.contains("suspended")) {
            return "suspended";
        }
        if (lower.contains("interrupted")) {
            return "interrupted";
        }
        return null;
    }

    private void logStatusDiagnostics(
            JsonNode row,
            String providerGameId,
            GameStatus normalizedStatus,
            String statusReason,
            GameCancelReason cancelReason,
            String rawCancelText
    ) {
        log.info(
                "[GameStatus] parser diagnostics providerGameId={} gameDate={} rawStatusCode={} rawStatusName={} rawStateText={} rawStatusText={} normalizedStatus={} statusReason={} cancelReason={}",
                providerGameId,
                firstText(row, "G_DT", "GAME_DATE", "GAME_DT"),
                text(row, "GAME_STATE_SC"),
                firstText(row, "GAME_STATE_SC_NM", "GAME_STATE_NM", "GAME_SC_NM", "STATUS_NM", "GAME_STATUS_NM"),
                statusFieldSummary(row, "GAME_STATE_SC_NM", "GAME_STATE_NM", "GAME_SC_NM"),
                statusFieldSummary(row, "STATUS_NM", "GAME_STATUS_NM", "CANCEL_SC_NM", "DETAIL_SC"),
                normalizedStatus.getApiValue(),
                statusReason,
                cancelReason == null ? clean(rawCancelText) : cancelReason.getApiValue()
        );
    }

    private String statusFieldSummary(JsonNode row, String... fieldNames) {
        List<String> parts = new ArrayList<>();
        for (String fieldName : fieldNames) {
            String value = text(row, fieldName);
            if (value != null) {
                parts.add(fieldName + "=" + value);
            }
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    private boolean isPostponedText(String value) {
        return value != null && (value.contains("연기") || value.contains("순연") || value.toLowerCase(java.util.Locale.ROOT).contains("postpon"));
    }

    private boolean isCancelledText(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return value.contains("취소") || value.contains("노게임") || lower.contains("cancel") || lower.contains("no game") || lower.contains("nogame");
    }

    private boolean isSuspendedText(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return value.contains("서스펜")
                || value.contains("우천중단")
                || value.contains("강우중단")
                || value.contains("경기중단")
                || value.contains("일시중단")
                || value.contains("중단")
                || lower.contains("suspend")
                || lower.contains("interrupted")
                || lower.contains("rain delay");
    }

    private boolean hasReliableFinalMarker(JsonNode row) {
        return row.path("GAME_RESULT_CK").asInt(0) == 1
                || isFinalText(statusText(row));
    }

    private String finalStatusReason(JsonNode row) {
        String rawStatusName = firstText(row, "GAME_STATE_SC_NM", "GAME_STATE_NM", "GAME_SC_NM", "STATUS_NM", "GAME_STATUS_NM");
        String rawCancelText = text(row, "CANCEL_SC_NM");
        for (String candidate : new String[]{rawStatusName, rawCancelText}) {
            String cleaned = clean(candidate);
            if (isFinalText(cleaned)) {
                return cleaned;
            }
        }
        return row.path("GAME_RESULT_CK").asInt(0) == 1 ? "GAME_RESULT_CK=1" : null;
    }

    private String statusText(JsonNode row) {
        String cancelName = text(row, "CANCEL_SC_NM");
        String rawStatusName = firstText(row, "GAME_STATE_SC_NM", "GAME_STATE_NM", "GAME_SC_NM", "STATUS_NM", "GAME_STATUS_NM");
        return joinClean(cancelName, rawStatusName);
    }

    private boolean isFinalText(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        String collapsed = value.replace(" ", "");
        return collapsed.contains("경기종료")
                || collapsed.equals("종료")
                || lower.contains("final")
                || lower.contains("ended")
                || lower.contains("completed");
    }

    private void logRunnerRelatedKeysIfNeeded(
            JsonNode row,
            String providerGameId,
            String firstBaseRunnerName,
            String secondBaseRunnerName,
            String thirdBaseRunnerName
    ) {
        if (!log.isDebugEnabled()) {
            return;
        }
        boolean occupied = baseBattingOrder(row, "B1") != null && baseBattingOrder(row, "B1") > 0
                || baseBattingOrder(row, "B2") != null && baseBattingOrder(row, "B2") > 0
                || baseBattingOrder(row, "B3") != null && baseBattingOrder(row, "B3") > 0;
        if (!occupied || firstBaseRunnerName != null || secondBaseRunnerName != null || thirdBaseRunnerName != null) {
            return;
        }
        List<String> keyPaths = new ArrayList<>();
        collectRunnerRelatedKeyPaths(row, "", keyPaths);
        log.debug("[BaseRunners] official runner key candidates provider_game_id={} keys={}", providerGameId, keyPaths);
    }

    private void collectRunnerRelatedKeyPaths(JsonNode node, String path, List<String> keyPaths) {
        if (!node.isObject()) {
            return;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            String keyPath = path.isBlank() ? name : path + "." + name;
            if (isRunnerRelatedKey(name)) {
                keyPaths.add(keyPath);
            }
            collectRunnerRelatedKeyPaths(node.path(name), keyPath, keyPaths);
        }
    }

    private boolean isRunnerRelatedKey(String key) {
        String upper = key.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("RUNNER")
                || upper.contains("BASE")
                || upper.contains("BATTER")
                || upper.contains("RUN")
                || upper.contains("B1")
                || upper.contains("B2")
                || upper.contains("B3");
    }

    private boolean hasRealScore(JsonNode row, GameStatus status) {
        String scoreCheck = text(row, "SCORE_CK");
        if ("1".equals(scoreCheck)) {
            return true;
        }
        return status == GameStatus.LIVE || status == GameStatus.SUSPENDED || status == GameStatus.FINAL;
    }

    private String resolveStatusReason(JsonNode row, GameStatus status, String rawCancelText) {
        if (status == GameStatus.FINAL) {
            return finalStatusReason(row);
        }
        if (status != GameStatus.CANCELLED && status != GameStatus.POSTPONED && status != GameStatus.SUSPENDED) {
            return null;
        }
        String rawStatusName = firstText(row, "GAME_STATE_SC_NM", "GAME_STATE_NM", "GAME_SC_NM", "STATUS_NM", "GAME_STATUS_NM");
        for (String candidate : new String[]{rawStatusName, rawCancelText}) {
            String cleaned = clean(candidate);
            if (cleaned != null && !isNormalStatusText(cleaned)) {
                return cleaned;
            }
        }
        return null;
    }

    private boolean isNormalStatusText(String value) {
        String collapsed = value == null ? "" : value.trim().replace(" ", "");
        return collapsed.equals("정상경기") || collapsed.equals("정상");
    }

    private GameCancelReason resolveCancelReason(GameStatus status, String rawCancelText) {
        if (status != GameStatus.CANCELLED) {
            return null;
        }
        if (rawCancelText == null || rawCancelText.isBlank()) {
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

    private boolean isGenericCancelText(String rawCancelText) {
        String collapsed = rawCancelText.replace(" ", "");
        return "취소".equals(collapsed) || "경기취소".equals(collapsed);
    }

    private String resolveHalf(String rawHalf) {
        if ("T".equals(rawHalf)) {
            return "top";
        }
        if ("B".equals(rawHalf)) {
            return "bottom";
        }
        return null;
    }

    private Integer integer(JsonNode row, String fieldName) {
        String value = text(row, fieldName);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String text(JsonNode row, String fieldName) {
        JsonNode node = row.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String gridText(JsonNode cell) {
        String value = text(cell, "Text");
        if (value == null) {
            return null;
        }
        String normalized = Jsoup.parse(value
                        .replace("<br />", "\n")
                        .replace("<br/>", "\n")
                        .replace("<br>", "\n")
                        .replace("&nbsp;", " "))
                .text()
                .replace('\u00A0', ' ')
                .trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String joinClean(String... values) {
        return java.util.Arrays.stream(values)
                .map(this::clean)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record ParsedGameDetail(
            String providerGameId,
            GameStatus status,
            boolean isCancelled,
            boolean isPostponed,
            GameCancelReason cancelReason,
            String rawCancelText,
            Integer awayScore,
            Integer homeScore,
            Integer inning,
            String inningHalf,
            String inningLabel,
            Integer balls,
            Integer strikes,
            Integer outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird,
            Integer firstBaseBattingOrder,
            Integer secondBaseBattingOrder,
            Integer thirdBaseBattingOrder,
            String firstBaseRunnerName,
            String secondBaseRunnerName,
            String thirdBaseRunnerName,
            String firstBaseRunnerId,
            String secondBaseRunnerId,
            String thirdBaseRunnerId,
            String currentPitcherName,
            String currentBatterName,
            String homeStartingPitcherName,
            String awayStartingPitcherName,
            boolean lineupAvailable,
            String statusReason,
            OffsetDateTime sourceUpdatedAt,
            String rawHash
    ) {
        public ParsedGameDetail(
                String providerGameId,
                GameStatus status,
                boolean isCancelled,
                boolean isPostponed,
                GameCancelReason cancelReason,
                String rawCancelText,
                Integer awayScore,
                Integer homeScore,
                Integer inning,
                String inningHalf,
                String inningLabel,
                Integer balls,
                Integer strikes,
                Integer outs,
                boolean runnerOnFirst,
                boolean runnerOnSecond,
                boolean runnerOnThird,
                String firstBaseRunnerName,
                String secondBaseRunnerName,
                String thirdBaseRunnerName,
                String firstBaseRunnerId,
                String secondBaseRunnerId,
                String thirdBaseRunnerId,
                String currentPitcherName,
                String currentBatterName,
                String homeStartingPitcherName,
                String awayStartingPitcherName,
                boolean lineupAvailable,
                String statusReason,
                OffsetDateTime sourceUpdatedAt,
                String rawHash
        ) {
            this(
                    providerGameId,
                    status,
                    isCancelled,
                    isPostponed,
                    cancelReason,
                    rawCancelText,
                    awayScore,
                    homeScore,
                    inning,
                    inningHalf,
                    inningLabel,
                    balls,
                    strikes,
                    outs,
                    runnerOnFirst,
                    runnerOnSecond,
                    runnerOnThird,
                    null,
                    null,
                    null,
                    firstBaseRunnerName,
                    secondBaseRunnerName,
                    thirdBaseRunnerName,
                    firstBaseRunnerId,
                    secondBaseRunnerId,
                    thirdBaseRunnerId,
                    currentPitcherName,
                    currentBatterName,
                    homeStartingPitcherName,
                    awayStartingPitcherName,
                    lineupAvailable,
                    statusReason,
                    sourceUpdatedAt,
                    rawHash
            );
        }

        public ParsedGameDetail(
                String providerGameId,
                GameStatus status,
                boolean isCancelled,
                boolean isPostponed,
                GameCancelReason cancelReason,
                String rawCancelText,
                Integer awayScore,
                Integer homeScore,
                Integer inning,
                String inningHalf,
                String inningLabel,
                Integer balls,
                Integer strikes,
                Integer outs,
                boolean runnerOnFirst,
                boolean runnerOnSecond,
                boolean runnerOnThird,
                String currentPitcherName,
                String currentBatterName,
                String homeStartingPitcherName,
                String awayStartingPitcherName,
                boolean lineupAvailable,
                String statusReason,
                OffsetDateTime sourceUpdatedAt,
                String rawHash
        ) {
            this(
                    providerGameId,
                    status,
                    isCancelled,
                    isPostponed,
                    cancelReason,
                    rawCancelText,
                    awayScore,
                    homeScore,
                    inning,
                    inningHalf,
                    inningLabel,
                    balls,
                    strikes,
                    outs,
                    runnerOnFirst,
                    runnerOnSecond,
                    runnerOnThird,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    currentPitcherName,
                    currentBatterName,
                    homeStartingPitcherName,
                    awayStartingPitcherName,
                    lineupAvailable,
                    statusReason,
                    sourceUpdatedAt,
                    rawHash
            );
        }

        public ParsedGameDetail withBaseRunnerNames(
                String firstBaseRunnerName,
                String secondBaseRunnerName,
                String thirdBaseRunnerName
        ) {
            return new ParsedGameDetail(
                    providerGameId,
                    status,
                    isCancelled,
                    isPostponed,
                    cancelReason,
                    rawCancelText,
                    awayScore,
                    homeScore,
                    inning,
                    inningHalf,
                    inningLabel,
                    balls,
                    strikes,
                    outs,
                    runnerOnFirst,
                    runnerOnSecond,
                    runnerOnThird,
                    firstBaseBattingOrder,
                    secondBaseBattingOrder,
                    thirdBaseBattingOrder,
                    runnerOnFirst ? firstBaseRunnerName : null,
                    runnerOnSecond ? secondBaseRunnerName : null,
                    runnerOnThird ? thirdBaseRunnerName : null,
                    firstBaseRunnerId,
                    secondBaseRunnerId,
                    thirdBaseRunnerId,
                    currentPitcherName,
                    currentBatterName,
                    homeStartingPitcherName,
                    awayStartingPitcherName,
                    lineupAvailable,
                    statusReason,
                    sourceUpdatedAt,
                    rawHash
            );
        }

        public ParsedGameDetail withScoreBoardStatus(ParsedScoreBoardStatus scoreBoardStatus) {
            if (scoreBoardStatus == null || scoreBoardStatus.status() == null) {
                return this;
            }
            return new ParsedGameDetail(
                    providerGameId,
                    scoreBoardStatus.status(),
                    false,
                    false,
                    scoreBoardStatus.status() == GameStatus.CANCELLED ? cancelReason : null,
                    scoreBoardStatus.status() == GameStatus.CANCELLED ? rawCancelText : null,
                    awayScore,
                    homeScore,
                    inning,
                    inningHalf,
                    inningLabel,
                    balls,
                    strikes,
                    outs,
                    runnerOnFirst,
                    runnerOnSecond,
                    runnerOnThird,
                    firstBaseBattingOrder,
                    secondBaseBattingOrder,
                    thirdBaseBattingOrder,
                    firstBaseRunnerName,
                    secondBaseRunnerName,
                    thirdBaseRunnerName,
                    firstBaseRunnerId,
                    secondBaseRunnerId,
                    thirdBaseRunnerId,
                    currentPitcherName,
                    currentBatterName,
                    homeStartingPitcherName,
                    awayStartingPitcherName,
                    lineupAvailable,
                    scoreBoardStatus.statusReason(),
                    sourceUpdatedAt,
                    rawHash
            );
        }
    }

    public record ParsedScoreBoardStatus(
            GameStatus status,
            String statusReason
    ) {
    }

    public record ParsedLineupData(
            List<ParsedLineupPlayer> away,
            List<ParsedLineupPlayer> home,
            String rawHash
    ) {
        public static ParsedLineupData empty(String rawHash) {
            return new ParsedLineupData(List.of(), List.of(), rawHash);
        }

        public boolean hasLineups() {
            return !away.isEmpty() || !home.isEmpty();
        }
    }

    public record ParsedLineupPlayer(
            String battingOrder,
            String position,
            String name
    ) {
    }
}
