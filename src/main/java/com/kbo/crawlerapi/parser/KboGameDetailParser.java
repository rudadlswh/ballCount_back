package com.kbo.crawlerapi.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
            JsonNode root = objectMapper.readTree(responseBody);
            List<ParsedGameDetail> details = new ArrayList<>();

            for (JsonNode row : root.path("game")) {
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
                GameCancelReason cancelReason = resolveCancelReason(status, rawCancelText);
                logCancellationStatusIfNeeded(row, providerGameId, status, cancelReason, rawCancelText);
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
                        "[BaseRunners] snapshot runners first={} second={} third={}",
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
                        status == GameStatus.CANCELLED || status == GameStatus.POSTPONED ? rawCancelText : null,
                        null,
                        hash(row.toString())
                ));
            }

            return details;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse KBO game detail response", exception);
        }
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
                "[BaseRunners] source=officialLineup mapped first={} second={} third={}",
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
        String statusText = String.join(" ", clean(cancelName), clean(rawStatusName)).trim();
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
        if ("3".equals(gameState) || row.path("GAME_RESULT_CK").asInt(0) == 1) {
            return GameStatus.FINAL;
        }
        if (isOfficiallyFinalByGameOverState(row)) {
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

    private void logCancellationStatusIfNeeded(
            JsonNode row,
            String providerGameId,
            GameStatus normalizedStatus,
            GameCancelReason cancelReason,
            String rawCancelText
    ) {
        if (normalizedStatus != GameStatus.CANCELLED
                && normalizedStatus != GameStatus.POSTPONED
                && normalizedStatus != GameStatus.SUSPENDED) {
            return;
        }
        log.info(
                "[GameStatus] cancellation detected providerGameId={} gameDate={} rawStatusCode={} rawStatusName={} normalizedStatus={} cancelReason={}",
                providerGameId,
                firstText(row, "G_DT", "GAME_DATE", "GAME_DT"),
                text(row, "GAME_STATE_SC"),
                firstText(row, "GAME_STATE_SC_NM", "GAME_STATE_NM", "GAME_SC_NM", "STATUS_NM", "GAME_STATUS_NM", "CANCEL_SC_NM"),
                normalizedStatus.getApiValue(),
                cancelReason == null ? clean(rawCancelText) : cancelReason.getApiValue()
        );
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
        return value.contains("서스펜") || value.contains("중단") || lower.contains("suspend");
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

    private boolean isOfficiallyFinalByGameOverState(JsonNode row) {
        Integer inning = integer(row, "GAME_INN_NO");
        Integer outs = integer(row, "OUT_CN");
        Integer awayScore = integer(row, "T_SCORE_CN");
        Integer homeScore = integer(row, "B_SCORE_CN");
        String inningHalf = resolveHalf(text(row, "GAME_TB_SC"));

        if (inning == null || inning < 9 || inningHalf == null || awayScore == null || homeScore == null) {
            return false;
        }
        if ("bottom".equals(inningHalf) && homeScore > awayScore) {
            return true;
        }
        if (outs == null || outs < 3 || awayScore.equals(homeScore)) {
            return false;
        }
        if ("top".equals(inningHalf) && homeScore > awayScore) {
            return true;
        }
        return "bottom".equals(inningHalf) && awayScore > homeScore;
    }

    private boolean hasRealScore(JsonNode row, GameStatus status) {
        String scoreCheck = text(row, "SCORE_CK");
        if ("1".equals(scoreCheck)) {
            return true;
        }
        return status == GameStatus.LIVE || status == GameStatus.FINAL;
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
