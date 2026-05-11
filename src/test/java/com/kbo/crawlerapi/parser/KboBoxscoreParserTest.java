package com.kbo.crawlerapi.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedBatterRecord;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedPitcherRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KboBoxscoreParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KboBoxscoreParser parser = new KboBoxscoreParser(objectMapper);

    @Test
    void parsesArrHitterGroupsInOfficialAwayThenHomeOrder() throws IOException {
        var result = parser.parse(fixture());

        assertThat(result.awayBatters()).hasSize(16);
        assertThat(result.homeBatters()).hasSize(13);
        assertThat(result.awayBatters().get(0).teamSide()).isEqualTo("away");
        assertThat(result.awayBatters().get(0).sourceGroupIndex()).isZero();
        assertThat(result.awayBatters().get(0).playerName()).isEqualTo("안상현");
        assertThat(result.homeBatters().get(0).teamSide()).isEqualTo("home");
        assertThat(result.homeBatters().get(0).sourceGroupIndex()).isEqualTo(1);
        assertThat(result.homeBatters().get(0).playerName()).isEqualTo("박찬호");
    }

    @Test
    void parsesHitterTable1LineupRowsAndPreservesSourceOrder() throws IOException {
        var result = parser.parse(fixture());

        ParsedBatterRecord firstAway = result.awayBatters().get(0);
        assertThat(firstAway.battingOrder()).isEqualTo(1);
        assertThat(firstAway.position()).isEqualTo("유");
        assertThat(firstAway.playerName()).isEqualTo("안상현");
        assertThat(firstAway.sourceOrder()).isZero();

        ParsedBatterRecord pinchHitter = result.awayBatters().get(1);
        assertThat(pinchHitter.battingOrder()).isEqualTo(1);
        assertThat(pinchHitter.position()).isEqualTo("타유");
        assertThat(pinchHitter.playerName()).isEqualTo("박성한");
        assertThat(pinchHitter.sourceOrder()).isEqualTo(1);
    }

    @Test
    void parsesHitterTable3TotalsAlignedByIndexWithTable1UsingObservedUnlabeledColumns() throws IOException {
        var result = parser.parse(fixture());

        ParsedBatterRecord awayFirst = result.awayBatters().get(0);
        assertThat(awayFirst.playerName()).isEqualTo("안상현");
        assertThat(awayFirst.atBats()).isEqualTo(3);
        assertThat(awayFirst.runs()).isEqualTo(1);
        assertThat(awayFirst.hits()).isZero();
        assertThat(awayFirst.rbi()).isZero();
        assertThat(awayFirst.battingAverage()).hasToString("0.300");

        ParsedBatterRecord homeThird = result.homeBatters().get(2);
        assertThat(homeThird.playerName()).isEqualTo("박준순");
        assertThat(homeThird.atBats()).isEqualTo(3);
        assertThat(homeThird.runs()).isEqualTo(1);
        assertThat(homeThird.hits()).isEqualTo(1);
        assertThat(homeThird.rbi()).isEqualTo(1);
        assertThat(homeThird.battingAverage()).hasToString("0.333");
    }

    @Test
    void leavesUnavailableHitterTotalsNullBecauseFixtureTable3DoesNotExposeThoseColumns() throws IOException {
        var result = parser.parse(fixture());

        ParsedBatterRecord batter = result.awayBatters().get(0);
        assertThat(batter.homeRuns()).isNull();
        assertThat(batter.walks()).isNull();
        assertThat(batter.strikeouts()).isNull();
        assertThat(batter.stolenBases()).isNull();
    }

    @Test
    void parsesArrPitcherGroupsInOfficialAwayThenHomeOrder() throws IOException {
        var result = parser.parse(fixture());

        assertThat(result.awayPitchers()).hasSize(6);
        assertThat(result.homePitchers()).hasSize(4);
        assertThat(result.awayPitchers().get(0).teamSide()).isEqualTo("away");
        assertThat(result.awayPitchers().get(0).sourceGroupIndex()).isZero();
        assertThat(result.awayPitchers().get(0).playerName()).isEqualTo("최민준");
        assertThat(result.homePitchers().get(0).teamSide()).isEqualTo("home");
        assertThat(result.homePitchers().get(0).sourceGroupIndex()).isEqualTo(1);
        assertThat(result.homePitchers().get(0).playerName()).isEqualTo("잭로그");
    }

    @Test
    void parsesPitcherRecordsByExplicitOfficialHeaders() throws IOException {
        var result = parser.parse(fixture());

        ParsedPitcherRecord awayStarter = result.awayPitchers().get(0);
        assertThat(awayStarter.pitchingOrder()).isEqualTo(1);
        assertThat(awayStarter.playerName()).isEqualTo("최민준");
        assertThat(awayStarter.appearance()).isEqualTo("선발");
        assertThat(awayStarter.decision()).isEqualTo("패");
        assertThat(awayStarter.wins()).isEqualTo(1);
        assertThat(awayStarter.losses()).isEqualTo(2);
        assertThat(awayStarter.saves()).isZero();
        assertThat(awayStarter.inningsPitched()).isEqualTo("2");
        assertThat(awayStarter.battersFaced()).isEqualTo(12);
        assertThat(awayStarter.pitchCount()).isEqualTo(46);
        assertThat(awayStarter.atBats()).isEqualTo(8);
        assertThat(awayStarter.hits()).isEqualTo(3);
        assertThat(awayStarter.homeRuns()).isEqualTo(1);
        assertThat(awayStarter.walksOrHitByPitch()).isEqualTo(3);
        assertThat(awayStarter.strikeouts()).isZero();
        assertThat(awayStarter.runs()).isEqualTo(3);
        assertThat(awayStarter.earnedRuns()).isEqualTo(2);
        assertThat(awayStarter.era()).hasToString("3.23");
        assertThat(awayStarter.sourceOrder()).isZero();

        ParsedPitcherRecord homeStarter = result.homePitchers().get(0);
        assertThat(homeStarter.playerName()).isEqualTo("잭로그");
        assertThat(homeStarter.decision()).isEqualTo("승");
        assertThat(homeStarter.inningsPitched()).isEqualTo("6 1/3");
        assertThat(homeStarter.walksOrHitByPitch()).isEqualTo(1);
        assertThat(homeStarter.strikeouts()).isEqualTo(5);
    }

    @Test
    void handlesMissingOrBlankTablesWithoutCrashing() throws JsonProcessingException {
        String payload = objectMapper.writeValueAsString(Map.of(
                "code", "100",
                "arrHitter", List.of(Map.of("table1", ""), Map.of()),
                "arrPitcher", List.of(Map.of("table", ""), Map.of())
        ));

        var result = parser.parse(payload);

        assertThat(result.awayBatters()).isEmpty();
        assertThat(result.homeBatters()).isEmpty();
        assertThat(result.awayPitchers()).isEmpty();
        assertThat(result.homePitchers()).isEmpty();
    }

    @Test
    void handlesCodeMsgOnlyResponseWithoutCrashing() {
        String payload = """
                {
                  "code": "200",
                  "msg": "입력 문자열의 형식이 잘못되었습니다."
                }
                """;

        var result = parser.parse(payload);

        assertThat(result.awayBatters()).isEmpty();
        assertThat(result.homeBatters()).isEmpty();
        assertThat(result.awayPitchers()).isEmpty();
        assertThat(result.homePitchers()).isEmpty();
    }

    @Test
    void handlesBlankDashAndMalformedNumericValuesSafely() throws JsonProcessingException {
        String lineupTable = table(List.of(), List.of(List.of("bad", "유", "테스트타자")));
        String hitterTotalsTable = table(List.of(), List.of(List.of("-", "", "x", "1", "bad")));
        String pitcherTable = table(
                List.of("선수명", "등판", "결과", "승", "패", "세", "이닝", "타자", "투구수", "타수", "피안타", "홈런", "4사구", "삼진", "실점", "자책", "평균자책점"),
                List.of(List.of("테스트투수", "선발", "&nbsp;", "x", "-", "", "1/3", "bad", "12", "3", "2", "1", "0", "bad", "1", "0", "-"))
        );
        String payload = objectMapper.writeValueAsString(Map.of(
                "code", "100",
                "arrHitter", List.of(
                        Map.of("table1", lineupTable, "table3", hitterTotalsTable),
                        Map.of("table1", lineupTable, "table3", hitterTotalsTable)
                ),
                "arrPitcher", List.of(
                        Map.of("table", pitcherTable),
                        Map.of("table", pitcherTable)
                )
        ));

        var result = parser.parse(payload);

        ParsedBatterRecord batter = result.awayBatters().get(0);
        assertThat(batter.battingOrder()).isNull();
        assertThat(batter.atBats()).isNull();
        assertThat(batter.runs()).isNull();
        assertThat(batter.hits()).isNull();
        assertThat(batter.rbi()).isEqualTo(1);
        assertThat(batter.battingAverage()).isNull();

        ParsedPitcherRecord pitcher = result.awayPitchers().get(0);
        assertThat(pitcher.decision()).isNull();
        assertThat(pitcher.wins()).isNull();
        assertThat(pitcher.losses()).isNull();
        assertThat(pitcher.saves()).isNull();
        assertThat(pitcher.battersFaced()).isNull();
        assertThat(pitcher.pitchCount()).isEqualTo(12);
        assertThat(pitcher.strikeouts()).isNull();
        assertThat(pitcher.era()).isNull();
    }

    private String fixture() throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/kbo/boxscore-final.json"));
    }

    private String table(List<String> headers, List<List<String>> rows) throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of(
                "headers", headers.isEmpty() ? List.of() : List.of(Map.of("row", cells(headers, "th"))),
                "rows", rows.stream()
                        .map(row -> Map.of("row", cells(row, null)))
                        .toList()
        ));
    }

    private List<Map<String, String>> cells(List<String> values, String typeObj) {
        return values.stream()
                .map(value -> Map.of(
                        "Text", value,
                        "TypeObj", typeObj == null ? "" : typeObj
                ))
                .toList();
    }
}
