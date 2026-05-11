package com.kbo.crawlerapi.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

@Component
public class KboBoxscoreParser {

    private final ObjectMapper objectMapper;

    public KboBoxscoreParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedBoxscore parse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!"100".equals(text(root, "code"))) {
                return ParsedBoxscore.empty();
            }

            JsonNode hitterGroups = root.path("arrHitter");
            JsonNode pitcherGroups = root.path("arrPitcher");
            return new ParsedBoxscore(
                    parseBatterGroup(hitterGroups.path(0), "away", 0),
                    parseBatterGroup(hitterGroups.path(1), "home", 1),
                    parsePitcherGroup(pitcherGroups.path(0), "away", 0),
                    parsePitcherGroup(pitcherGroups.path(1), "home", 1)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse KBO boxscore response", exception);
        }
    }

    private List<ParsedBatterRecord> parseBatterGroup(JsonNode group, String teamSide, int sourceGroupIndex) throws IOException {
        JsonNode lineupTable = parseEmbeddedJson(text(group, "table1"));
        JsonNode totalsTable = parseEmbeddedJson(text(group, "table3"));
        if (lineupTable == null) {
            return List.of();
        }

        JsonNode lineupRows = lineupTable.path("rows");
        JsonNode totalRows = totalsTable == null ? objectMapper.createArrayNode() : totalsTable.path("rows");
        if (!lineupRows.isArray()) {
            return List.of();
        }

        List<ParsedBatterRecord> records = new ArrayList<>();
        for (int index = 0; index < lineupRows.size(); index++) {
            JsonNode lineupCells = lineupRows.path(index).path("row");
            if (!lineupCells.isArray() || lineupCells.size() < 3) {
                continue;
            }

            String playerName = cellText(lineupCells, 2);
            if (playerName == null) {
                continue;
            }

            JsonNode totalCells = totalRows.path(index).path("row");
            // Official hitter table3 has no headers in the captured 20260510SKOB0 fixture.
            // The observed aligned columns are AB, R, H, RBI, AVG. HR/BB/SO/SB are not separate totals.
            records.add(new ParsedBatterRecord(
                    teamSide,
                    sourceGroupIndex,
                    parseInteger(cellText(lineupCells, 0)),
                    cellText(lineupCells, 1),
                    playerName,
                    parseInteger(cellText(totalCells, 0)),
                    parseInteger(cellText(totalCells, 1)),
                    parseInteger(cellText(totalCells, 2)),
                    parseInteger(cellText(totalCells, 3)),
                    null,
                    null,
                    null,
                    null,
                    parseDecimal(cellText(totalCells, 4)),
                    index
            ));
        }
        return records;
    }

    private List<ParsedPitcherRecord> parsePitcherGroup(JsonNode group, String teamSide, int sourceGroupIndex) throws IOException {
        JsonNode table = parseEmbeddedJson(text(group, "table"));
        if (table == null || !table.path("rows").isArray()) {
            return List.of();
        }

        List<String> headers = headerTexts(table);
        List<ParsedPitcherRecord> records = new ArrayList<>();
        JsonNode rows = table.path("rows");
        for (int index = 0; index < rows.size(); index++) {
            JsonNode cells = rows.path(index).path("row");
            String playerName = valueByHeader(headers, cells, "선수명");
            if (playerName == null) {
                continue;
            }

            records.add(new ParsedPitcherRecord(
                    teamSide,
                    sourceGroupIndex,
                    index + 1,
                    playerName,
                    valueByHeader(headers, cells, "등판"),
                    valueByHeader(headers, cells, "결과"),
                    parseInteger(valueByHeader(headers, cells, "승")),
                    parseInteger(valueByHeader(headers, cells, "패")),
                    parseInteger(valueByHeader(headers, cells, "세")),
                    valueByHeader(headers, cells, "이닝"),
                    parseInteger(valueByHeader(headers, cells, "타자")),
                    parseInteger(valueByHeader(headers, cells, "투구수")),
                    parseInteger(valueByHeader(headers, cells, "타수")),
                    parseInteger(valueByHeader(headers, cells, "피안타")),
                    parseInteger(valueByHeader(headers, cells, "홈런")),
                    parseInteger(valueByHeader(headers, cells, "4사구")),
                    parseInteger(valueByHeader(headers, cells, "삼진")),
                    parseInteger(valueByHeader(headers, cells, "실점")),
                    parseInteger(valueByHeader(headers, cells, "자책")),
                    parseDecimal(valueByHeader(headers, cells, "평균자책점")),
                    index
            ));
        }
        return records;
    }

    private JsonNode parseEmbeddedJson(String value) throws IOException {
        if (value == null) {
            return null;
        }
        return objectMapper.readTree(value);
    }

    private List<String> headerTexts(JsonNode table) {
        JsonNode headerRow = table.path("headers").path(0).path("row");
        if (!headerRow.isArray()) {
            return List.of();
        }
        List<String> headers = new ArrayList<>();
        for (JsonNode headerCell : headerRow) {
            headers.add(gridText(text(headerCell, "Text")));
        }
        return headers;
    }

    private String valueByHeader(List<String> headers, JsonNode cells, String header) {
        int index = headers.indexOf(header);
        if (index < 0) {
            return null;
        }
        return cellText(cells, index);
    }

    private String cellText(JsonNode cells, int index) {
        if (!cells.isArray() || cells.size() <= index) {
            return null;
        }
        return gridText(text(cells.get(index), "Text"));
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String gridText(String value) {
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
        if (normalized.isBlank() || "-".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    private Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record ParsedBoxscore(
            List<ParsedBatterRecord> awayBatters,
            List<ParsedBatterRecord> homeBatters,
            List<ParsedPitcherRecord> awayPitchers,
            List<ParsedPitcherRecord> homePitchers
    ) {
        public static ParsedBoxscore empty() {
            return new ParsedBoxscore(List.of(), List.of(), List.of(), List.of());
        }
    }

    public record ParsedBatterRecord(
            String teamSide,
            int sourceGroupIndex,
            Integer battingOrder,
            String position,
            String playerName,
            Integer atBats,
            Integer runs,
            Integer hits,
            Integer rbi,
            Integer homeRuns,
            Integer walks,
            Integer strikeouts,
            Integer stolenBases,
            BigDecimal battingAverage,
            int sourceOrder
    ) {
    }

    public record ParsedPitcherRecord(
            String teamSide,
            int sourceGroupIndex,
            int pitchingOrder,
            String playerName,
            String appearance,
            String decision,
            Integer wins,
            Integer losses,
            Integer saves,
            String inningsPitched,
            Integer battersFaced,
            Integer pitchCount,
            Integer atBats,
            Integer hits,
            Integer homeRuns,
            Integer walksOrHitByPitch,
            Integer strikeouts,
            Integer runs,
            Integer earnedRuns,
            BigDecimal era,
            int sourceOrder
    ) {
    }
}
