package com.kbo.crawlerapi.parser;

import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedBatterRecord;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedBoxscore;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedPitcherRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class KboLiveTextParser {

    private static final List<String> REQUIRED_BATTER_HEADERS = List.of("타자", "타수", "득점", "안타", "홈런", "타점", "도루", "희타", "볼넷", "삼진", "병살", "실책");
    private static final List<String> REQUIRED_PITCHER_HEADERS = List.of("투수", "이닝", "타자", "투구", "타수", "안타", "홈런", "희타", "볼넷", "삼진", "실점", "자책");
    private static final Pattern INNING_PATTERN = Pattern.compile("(\\d+)\\s*회\\s*(초|말)");

    public ParsedLiveText parse(String html) {
        if (html == null || html.isBlank()) {
            return ParsedLiveText.empty();
        }
        Document document = Jsoup.parse(html);
        List<ParsedLiveTextBatterRecord> awayBatters = new ArrayList<>();
        List<ParsedLiveTextBatterRecord> homeBatters = new ArrayList<>();
        List<ParsedLiveTextPitcherRecord> awayPitchers = new ArrayList<>();
        List<ParsedLiveTextPitcherRecord> homePitchers = new ArrayList<>();
        int batterTableIndex = 0;
        int pitcherTableIndex = 0;

        for (Element table : document.select("table")) {
            List<String> headers = headers(table);
            if (containsAllHeaders(headers, REQUIRED_BATTER_HEADERS)) {
                String teamSide = batterTableIndex == 0 ? "away" : "home";
                List<ParsedLiveTextBatterRecord> records = parseBatterTable(table, headers, teamSide, batterTableIndex);
                if ("away".equals(teamSide)) {
                    awayBatters.addAll(records);
                } else {
                    homeBatters.addAll(records);
                }
                batterTableIndex++;
            } else if (containsAllHeaders(headers, REQUIRED_PITCHER_HEADERS)) {
                String teamSide = pitcherTableIndex == 0 ? "away" : "home";
                List<ParsedLiveTextPitcherRecord> records = parsePitcherTable(table, headers, teamSide, pitcherTableIndex);
                if ("away".equals(teamSide)) {
                    awayPitchers.addAll(records);
                } else {
                    homePitchers.addAll(records);
                }
                pitcherTableIndex++;
            }
        }

        return new ParsedLiveText(
                awayBatters,
                homeBatters,
                awayPitchers,
                homePitchers,
                parseEvents(document)
        );
    }

    private List<ParsedLiveTextBatterRecord> parseBatterTable(Element table, List<String> headers, String teamSide, int sourceGroupIndex) {
        List<ParsedLiveTextBatterRecord> records = new ArrayList<>();
        List<Element> rows = dataRows(table);
        for (int index = 0; index < rows.size(); index++) {
            Element row = rows.get(index);
            List<String> cells = rowCells(row);
            String playerName = valueByHeader(headers, cells, "타자");
            if (!hasText(playerName)) {
                continue;
            }
            records.add(new ParsedLiveTextBatterRecord(
                    teamSide,
                    sourceGroupIndex,
                    parseInteger(firstByHeader(headers, cells, "타순", "순번", "순")),
                    firstByHeader(headers, cells, "포지션", "위치", "수비"),
                    playerName,
                    parseInteger(valueByHeader(headers, cells, "타수")),
                    parseInteger(valueByHeader(headers, cells, "득점")),
                    parseInteger(valueByHeader(headers, cells, "안타")),
                    parseInteger(valueByHeader(headers, cells, "타점")),
                    parseInteger(valueByHeader(headers, cells, "홈런")),
                    parseInteger(valueByHeader(headers, cells, "볼넷")),
                    parseInteger(valueByHeader(headers, cells, "삼진")),
                    parseInteger(valueByHeader(headers, cells, "도루")),
                    parseInteger(valueByHeader(headers, cells, "희타")),
                    parseInteger(valueByHeader(headers, cells, "병살")),
                    parseInteger(valueByHeader(headers, cells, "실책")),
                    index
            ));
        }
        return records;
    }

    private List<ParsedLiveTextPitcherRecord> parsePitcherTable(Element table, List<String> headers, String teamSide, int sourceGroupIndex) {
        List<ParsedLiveTextPitcherRecord> records = new ArrayList<>();
        List<Element> rows = dataRows(table);
        for (int index = 0; index < rows.size(); index++) {
            Element row = rows.get(index);
            List<String> cells = rowCells(row);
            String playerName = valueByHeader(headers, cells, "투수");
            if (!hasText(playerName)) {
                continue;
            }
            records.add(new ParsedLiveTextPitcherRecord(
                    teamSide,
                    sourceGroupIndex,
                    index + 1,
                    playerName,
                    firstByHeader(headers, cells, "등판", "구분"),
                    firstByHeader(headers, cells, "결과"),
                    valueByHeader(headers, cells, "이닝"),
                    parseInteger(valueByHeader(headers, cells, "타자")),
                    parseInteger(valueByHeader(headers, cells, "투구")),
                    parseInteger(valueByHeader(headers, cells, "타수")),
                    parseInteger(valueByHeader(headers, cells, "안타")),
                    parseInteger(valueByHeader(headers, cells, "홈런")),
                    parseInteger(valueByHeader(headers, cells, "희타")),
                    parseInteger(valueByHeader(headers, cells, "볼넷")),
                    parseInteger(valueByHeader(headers, cells, "삼진")),
                    parseInteger(valueByHeader(headers, cells, "실점")),
                    parseInteger(valueByHeader(headers, cells, "자책")),
                    index
            ));
        }
        return records;
    }

    private List<ParsedLiveTextEvent> parseEvents(Document document) {
        List<ParsedLiveTextEvent> events = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Integer currentInning = null;
        String currentHalf = null;
        int sequence = 1;
        for (Element element : document.select("li, p, td, div, span")) {
            if (!element.children().isEmpty()) {
                continue;
            }
            String text = normalize(element.text());
            if (!hasText(text)) {
                continue;
            }
            Matcher inningMatcher = INNING_PATTERN.matcher(text);
            if (inningMatcher.find()) {
                currentInning = parseInteger(inningMatcher.group(1));
                currentHalf = "초".equals(inningMatcher.group(2)) ? "top" : "bottom";
            }
            if (!looksLikeEvent(text)) {
                continue;
            }
            String key = currentInning + "|" + currentHalf + "|" + text;
            if (!seen.add(key)) {
                continue;
            }
            events.add(new ParsedLiveTextEvent(
                    sequence++,
                    currentInning,
                    currentHalf,
                    eventType(text),
                    text,
                    playerName(text)
            ));
        }
        return events;
    }

    private List<String> headers(Element table) {
        for (Element row : table.select("tr")) {
            List<String> cells = rowCells(row);
            if (!cells.isEmpty()) {
                return cells;
            }
        }
        return List.of();
    }

    private List<Element> dataRows(Element table) {
        List<Element> rows = table.select("tr");
        if (rows.size() <= 1) {
            return List.of();
        }
        return rows.subList(1, rows.size());
    }

    private List<String> rowCells(Element row) {
        return row.select("th,td").stream()
                .map(cell -> normalize(cell.text()))
                .toList();
    }

    private boolean containsAllHeaders(List<String> headers, List<String> requiredHeaders) {
        return requiredHeaders.stream().allMatch(required -> headers.stream().anyMatch(required::equals));
    }

    private String valueByHeader(List<String> headers, List<String> cells, String header) {
        int index = headers.indexOf(header);
        if (index < 0 || cells.size() <= index) {
            return null;
        }
        return emptyToNull(cells.get(index));
    }

    private String firstByHeader(List<String> headers, List<String> cells, String... names) {
        for (String name : names) {
            String value = valueByHeader(headers, cells, name);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean looksLikeEvent(String text) {
        return text.contains(" : ")
                || text.contains(":")
                || text.contains("투수 ")
                || text.contains("승리투수")
                || text.contains("패전투수")
                || text.contains("경기종료");
    }

    private String eventType(String text) {
        String normalized = text.replace(" ", "");
        if (normalized.contains("승리투수")) {
            return "WINNING_PITCHER";
        }
        if (normalized.contains("패전투수")) {
            return "LOSING_PITCHER";
        }
        if (normalized.contains("투수") && normalized.contains("교체")) {
            return "PITCHING_CHANGE";
        }
        if (normalized.contains("대수비") || normalized.contains("수비") && normalized.contains("교체")) {
            return "DEFENSIVE_CHANGE";
        }
        if (normalized.contains("홈런")) {
            return "HOME_RUN";
        }
        if (normalized.contains("볼넷") || normalized.contains("4구")) {
            return "WALK";
        }
        if (normalized.contains("사구") || normalized.contains("몸에맞")) {
            return "HIT_BY_PITCH";
        }
        if (normalized.contains("삼진")) {
            return "STRIKEOUT";
        }
        if (normalized.contains("병살")) {
            return "DOUBLE_PLAY";
        }
        if (normalized.contains("실책")) {
            return "ERROR";
        }
        if (normalized.contains("도루")) {
            return "STOLEN_BASE";
        }
        if (normalized.contains("폭투")) {
            return "WILD_PITCH";
        }
        if (normalized.contains("포일")) {
            return "PASSED_BALL";
        }
        if (normalized.contains("희생") || normalized.contains("희번") || normalized.contains("희플")) {
            return "SACRIFICE";
        }
        if (normalized.contains("홈인") || normalized.contains("득점")) {
            return "RUN_SCORED";
        }
        if (normalized.contains("안타") || normalized.contains("1루타") || normalized.contains("2루타") || normalized.contains("3루타")) {
            return "HIT";
        }
        if (normalized.contains("아웃") || normalized.contains("땅볼") || normalized.contains("뜬공") || normalized.contains("직선타")) {
            return "OUT";
        }
        if (normalized.contains("경기종료")) {
            return "GAME_END";
        }
        return "UNKNOWN";
    }

    private String playerName(String text) {
        String normalized = normalize(text);
        int colonIndex = normalized.indexOf(':');
        if (colonIndex > 0) {
            String before = normalized.substring(0, colonIndex).trim();
            if (before.startsWith("투수 ")) {
                before = before.substring("투수 ".length()).trim();
            }
            if (before.contains("주자 ")) {
                before = before.substring(before.indexOf("주자 ") + "주자 ".length()).trim();
            }
            return emptyToNull(before);
        }
        if (normalized.startsWith("승리투수") || normalized.startsWith("패전투수")) {
            int index = normalized.indexOf(':');
            return index >= 0 ? emptyToNull(normalized.substring(index + 1).trim()) : null;
        }
        return null;
    }

    private Integer parseInteger(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.replace(",", "").trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private String emptyToNull(String value) {
        String normalized = normalize(value);
        return normalized == null || normalized.isBlank() || "-".equals(normalized) ? null : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ParsedLiveText(
            List<ParsedLiveTextBatterRecord> awayBatters,
            List<ParsedLiveTextBatterRecord> homeBatters,
            List<ParsedLiveTextPitcherRecord> awayPitchers,
            List<ParsedLiveTextPitcherRecord> homePitchers,
            List<ParsedLiveTextEvent> events
    ) {
        public static ParsedLiveText empty() {
            return new ParsedLiveText(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public boolean hasRecords() {
            return !awayBatters.isEmpty() || !homeBatters.isEmpty() || !awayPitchers.isEmpty() || !homePitchers.isEmpty();
        }

        public boolean hasEvents() {
            return !events.isEmpty();
        }

        public ParsedBoxscore toParsedBoxscore() {
            return new ParsedBoxscore(
                    awayBatters.stream().map(ParsedLiveTextBatterRecord::toParsedBatterRecord).toList(),
                    homeBatters.stream().map(ParsedLiveTextBatterRecord::toParsedBatterRecord).toList(),
                    awayPitchers.stream().map(ParsedLiveTextPitcherRecord::toParsedPitcherRecord).toList(),
                    homePitchers.stream().map(ParsedLiveTextPitcherRecord::toParsedPitcherRecord).toList()
            );
        }
    }

    public record ParsedLiveTextBatterRecord(
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
            Integer sacrificeHits,
            Integer groundedIntoDoublePlay,
            Integer errors,
            int sourceOrder
    ) {
        private ParsedBatterRecord toParsedBatterRecord() {
            return new ParsedBatterRecord(
                    teamSide,
                    sourceGroupIndex,
                    battingOrder,
                    position,
                    playerName,
                    atBats,
                    runs,
                    hits,
                    rbi,
                    homeRuns,
                    walks,
                    strikeouts,
                    stolenBases,
                    null,
                    sourceOrder
            );
        }
    }

    public record ParsedLiveTextPitcherRecord(
            String teamSide,
            int sourceGroupIndex,
            int pitchingOrder,
            String playerName,
            String appearance,
            String decision,
            String inningsPitched,
            Integer battersFaced,
            Integer pitchCount,
            Integer atBats,
            Integer hits,
            Integer homeRuns,
            Integer sacrificeHits,
            Integer walks,
            Integer strikeouts,
            Integer runs,
            Integer earnedRuns,
            int sourceOrder
    ) {
        private ParsedPitcherRecord toParsedPitcherRecord() {
            return new ParsedPitcherRecord(
                    teamSide,
                    sourceGroupIndex,
                    pitchingOrder,
                    playerName,
                    appearance,
                    decision,
                    null,
                    null,
                    null,
                    inningsPitched,
                    battersFaced,
                    pitchCount,
                    atBats,
                    hits,
                    homeRuns,
                    walks,
                    strikeouts,
                    runs,
                    earnedRuns,
                    null,
                    sourceOrder
            );
        }
    }

    public record ParsedLiveTextEvent(
            int sequenceNumber,
            Integer inning,
            String inningHalf,
            String eventType,
            String eventText,
            String playerName
    ) {
    }
}
