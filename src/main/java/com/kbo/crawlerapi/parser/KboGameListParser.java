package com.kbo.crawlerapi.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.GameStatus;

@Component
public class KboGameListParser {

    private final ObjectMapper objectMapper;

    public KboGameListParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ParsedGameListGame> parseGameList(String responseBody) {
        try {
            JsonNode root = normalizeRoot(objectMapper.readTree(responseBody));
            List<ParsedGameListGame> games = new ArrayList<>();
            for (JsonNode row : gameRows(root)) {
                String providerGameId = text(row, "G_ID");
                if (!hasText(providerGameId)) {
                    continue;
                }
                games.add(new ParsedGameListGame(
                        providerGameId,
                        text(row, "AWAY_NM"),
                        text(row, "HOME_NM"),
                        text(row, "T_PIT_P_NM"),
                        text(row, "B_PIT_P_NM"),
                        resolveStatus(row),
                        statusReason(row)
                ));
            }
            return games;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse KBO game list response", exception);
        }
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

    private List<JsonNode> gameRows(JsonNode root) {
        for (String path : List.of("game", "Game", "games", "rows", "list", "data.game", "data.games", "data.rows", "data.list")) {
            JsonNode node = nodeAt(root, path);
            if (node.isArray()) {
                List<JsonNode> rows = new ArrayList<>();
                node.forEach(rows::add);
                return rows;
            }
        }
        if (root.isArray()) {
            List<JsonNode> rows = new ArrayList<>();
            root.forEach(rows::add);
            return rows;
        }
        return List.of();
    }

    private JsonNode nodeAt(JsonNode root, String dotPath) {
        JsonNode current = root;
        for (String segment : dotPath.split("\\.")) {
            current = current.path(segment);
            if (current.isMissingNode() || current.isNull()) {
                return current;
            }
        }
        return current;
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isBlank() ? null : text;
    }

    private GameStatus resolveStatus(JsonNode row) {
        String statusText = statusText(row);
        if (statusText == null) {
            return null;
        }
        if (isSuspensionText(statusText)) {
            return GameStatus.SUSPENDED;
        }
        if (isDelayText(statusText)) {
            return GameStatus.DELAYED;
        }
        return null;
    }

    private String statusReason(JsonNode row) {
        String statusText = statusText(row);
        if (statusText == null) {
            return null;
        }
        if (resolveStatus(row) == null) {
            return null;
        }
        return statusText;
    }

    private String statusText(JsonNode row) {
        return joinClean(
                text(row, "CANCEL_SC_NM"),
                firstText(row, "GAME_STATE_SC_NM", "GAME_STATE_NM", "GAME_SC_NM", "STATUS_NM", "GAME_STATUS_NM")
        );
    }

    private String firstText(JsonNode row, String... keys) {
        for (String key : keys) {
            String value = text(row, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String joinClean(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parts.add(value.trim());
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
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
                || value.contains("개시 지연")
                || value.contains("개시지연")
                || value.contains("지연")
                || lower.contains("rain delay")
                || lower.contains("delayed")
                || lower.contains("delay");
    }

    private boolean isSuspensionText(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("서스펜")
                || value.contains("우천중단")
                || value.contains("우천 중단")
                || value.contains("경기중단")
                || value.contains("경기 중단")
                || value.contains("일시중단")
                || value.contains("일시 중단")
                || lower.contains("suspend")
                || lower.contains("suspended")
                || lower.contains("interrupted");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ParsedGameListGame(
            String providerGameId,
            String awayProviderTeamName,
            String homeProviderTeamName,
            String awayStartingPitcherName,
            String homeStartingPitcherName,
            GameStatus status,
            String statusReason
    ) {
        public boolean hasStartingPitcherName() {
            return hasText(awayStartingPitcherName) || hasText(homeStartingPitcherName);
        }

        private boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        public String naturalKey() {
            return naturalKey(awayProviderTeamName, homeProviderTeamName);
        }

        public static String naturalKey(String awayProviderTeamName, String homeProviderTeamName) {
            return normalize(awayProviderTeamName) + "|" + normalize(homeProviderTeamName);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }
}
