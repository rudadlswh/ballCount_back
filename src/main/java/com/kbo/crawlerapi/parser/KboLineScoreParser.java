package com.kbo.crawlerapi.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.support.HashSupport;

@Component
public class KboLineScoreParser {

    private final ObjectMapper objectMapper;

    public KboLineScoreParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedLineScoreResult parse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!"100".equals(root.path("code").asText())) {
                return ParsedLineScoreResult.empty(hash(responseBody));
            }

            JsonNode table2 = parseEmbeddedJson(root.path("table2").asText(null));
            JsonNode table3 = parseEmbeddedJson(root.path("table3").asText(null));
            if (table2 == null || table3 == null) {
                return ParsedLineScoreResult.empty(hash(responseBody));
            }

            List<Integer> inningNumbers = parseHeaders(table2.path("headers"));
            List<JsonNode> rows = toList(table2.path("rows"));
            if (rows.size() < 2 || inningNumbers.isEmpty()) {
                return ParsedLineScoreResult.empty(hash(responseBody));
            }

            List<Integer> awayRunsByInning = parseRuns(rows.get(0).path("row"), inningNumbers.size());
            List<Integer> homeRunsByInning = parseRuns(rows.get(1).path("row"), inningNumbers.size());

            List<ParsedLineScoreInning> innings = new ArrayList<>();
            for (int index = 0; index < inningNumbers.size(); index++) {
                Integer awayRuns = awayRunsByInning.get(index);
                Integer homeRuns = homeRunsByInning.get(index);
                if (awayRuns == null && homeRuns == null) {
                    continue;
                }
                innings.add(new ParsedLineScoreInning(inningNumbers.get(index), awayRuns, homeRuns));
            }

            ParsedTeamTotals awayTotals = parseTotals(table3.path("rows"), 0);
            ParsedTeamTotals homeTotals = parseTotals(table3.path("rows"), 1);

            return new ParsedLineScoreResult(innings, awayTotals, homeTotals, hash(responseBody));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse KBO line score response", exception);
        }
    }

    private JsonNode parseEmbeddedJson(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return null;
        }
        return objectMapper.readTree(value);
    }

    private List<Integer> parseHeaders(JsonNode headersNode) {
        List<Integer> inningNumbers = new ArrayList<>();
        if (!headersNode.isArray() || headersNode.isEmpty()) {
            return inningNumbers;
        }
        JsonNode row = headersNode.get(0).path("row");
        for (JsonNode headerCell : row) {
            Integer inningNumber = parseInteger(headerCell.path("Text").asText(null));
            if (inningNumber != null) {
                inningNumbers.add(inningNumber);
            }
        }
        return inningNumbers;
    }

    private List<Integer> parseRuns(JsonNode cells, int maxColumns) {
        List<Integer> values = new ArrayList<>(maxColumns);
        for (int index = 0; index < maxColumns; index++) {
            JsonNode cell = cells.path(index);
            values.add(parseInteger(cell.path("Text").asText(null)));
        }
        return values;
    }

    private ParsedTeamTotals parseTotals(JsonNode totalsRows, int rowIndex) {
        if (!totalsRows.isArray() || totalsRows.size() <= rowIndex) {
            return ParsedTeamTotals.empty();
        }
        JsonNode row = totalsRows.get(rowIndex).path("row");
        return new ParsedTeamTotals(
                parseInteger(row.path(0).path("Text").asText(null)),
                parseInteger(row.path(1).path("Text").asText(null)),
                parseInteger(row.path(2).path("Text").asText(null)),
                parseInteger(row.path(3).path("Text").asText(null))
        );
    }

    private List<JsonNode> toList(JsonNode node) {
        List<JsonNode> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(values::add);
        }
        return values;
    }

    private Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isBlank() || "-".equals(normalized)) {
            return null;
        }
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String hash(String value) {
        return HashSupport.sha256Hex(value);
    }

    public record ParsedLineScoreInning(
            int inningNumber,
            Integer awayRuns,
            Integer homeRuns
    ) {
    }

    public record ParsedTeamTotals(
            Integer runs,
            Integer hits,
            Integer errors,
            Integer balls
    ) {
        public static ParsedTeamTotals empty() {
            return new ParsedTeamTotals(null, null, null, null);
        }

        public boolean isEmpty() {
            return runs == null && hits == null && errors == null && balls == null;
        }
    }

    public record ParsedLineScoreResult(
            List<ParsedLineScoreInning> innings,
            ParsedTeamTotals awayTotals,
            ParsedTeamTotals homeTotals,
            String rawHash
    ) {
        public static ParsedLineScoreResult empty(String rawHash) {
            return new ParsedLineScoreResult(List.of(), ParsedTeamTotals.empty(), ParsedTeamTotals.empty(), rawHash);
        }
    }
}
