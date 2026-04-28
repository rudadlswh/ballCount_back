package com.kbo.crawlerapi.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;

@Component
public class KboGameDetailParser {

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
                String awayStartingPitcherName = text(row, "T_PIT_P_NM");
                String homeStartingPitcherName = text(row, "B_PIT_P_NM");
                boolean lineupAvailable = integer(row, "LINEUP_CK") != null && integer(row, "LINEUP_CK") > 0;

                details.add(new ParsedGameDetail(
                        providerGameId,
                        status,
                        status == GameStatus.CANCELLED,
                        status == GameStatus.POSTPONED,
                        resolveCancelReason(status, rawCancelText),
                        status == GameStatus.CANCELLED ? rawCancelText : null,
                        awayScore,
                        homeScore,
                        inning,
                        inningHalf,
                        inningLabel,
                        integer(row, "BALL_CN"),
                        integer(row, "STRIKE_CN"),
                        integer(row, "OUT_CN"),
                        integer(row, "B1_BAT_ORDER_NO") != null && integer(row, "B1_BAT_ORDER_NO") > 0,
                        integer(row, "B2_BAT_ORDER_NO") != null && integer(row, "B2_BAT_ORDER_NO") > 0,
                        integer(row, "B3_BAT_ORDER_NO") != null && integer(row, "B3_BAT_ORDER_NO") > 0,
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

    private GameStatus resolveStatus(JsonNode row) {
        String cancelName = text(row, "CANCEL_SC_NM");
        if (cancelName != null) {
            if (cancelName.contains("연기")) {
                return GameStatus.POSTPONED;
            }
            if (cancelName.contains("취소")) {
                return GameStatus.CANCELLED;
            }
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
            String homeStartingPitcherName,
            String awayStartingPitcherName,
            boolean lineupAvailable,
            String statusReason,
            OffsetDateTime sourceUpdatedAt,
            String rawHash
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
