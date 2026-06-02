package com.kbo.crawlerapi.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
                        text(row, "B_PIT_P_NM")
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ParsedGameListGame(
            String providerGameId,
            String awayProviderTeamName,
            String homeProviderTeamName,
            String awayStartingPitcherName,
            String homeStartingPitcherName
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
