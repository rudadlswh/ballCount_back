package com.kbo.crawlerapi.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KboLiveTextParserTest {

    private final KboLiveTextParser parser = new KboLiveTextParser();

    @Test
    void extractsAwayDetailedBatterRecordsFromLineupTableAndIgnoresSummaryBoxscore() {
        var result = parser.parse(fixtureHtml());

        assertThat(result.awayBatters()).hasSize(2);
        assertThat(result.awayBatters().get(0).playerName()).isEqualTo("박승욱");
        assertThat(result.awayBatters().get(0).atBats()).isEqualTo(4);
        assertThat(result.awayBatters().get(0).runs()).isEqualTo(1);
        assertThat(result.awayBatters().get(0).hits()).isEqualTo(2);
        assertThat(result.awayBatters().get(0).rbi()).isEqualTo(1);
        assertThat(result.awayBatters()).extracting(KboLiveTextParser.ParsedLiveTextBatterRecord::playerName)
                .doesNotContain("요약타자");
    }

    @Test
    void extractsHomeDetailedBatterRecordsAndExtendedStats() {
        var result = parser.parse(fixtureHtml());

        assertThat(result.homeBatters()).hasSize(2);
        var batter = result.homeBatters().get(0);
        assertThat(batter.playerName()).isEqualTo("김주원");
        assertThat(batter.homeRuns()).isEqualTo(1);
        assertThat(batter.walks()).isEqualTo(2);
        assertThat(batter.strikeouts()).isEqualTo(1);
        assertThat(batter.groundedIntoDoublePlay()).isEqualTo(1);
        assertThat(batter.errors()).isEqualTo(1);
    }

    @Test
    void extractsAwayPitcherRecordsWithDisplayedOrderAndReliefPitchers() {
        var result = parser.parse(fixtureHtml());

        assertThat(result.awayPitchers()).hasSize(2);
        assertThat(result.awayPitchers().get(0).playerName()).isEqualTo("박세웅");
        assertThat(result.awayPitchers().get(0).pitchingOrder()).isEqualTo(1);
        assertThat(result.awayPitchers().get(0).inningsPitched()).isEqualTo("6");
        assertThat(result.awayPitchers().get(1).playerName()).isEqualTo("김원중");
        assertThat(result.awayPitchers().get(1).pitchingOrder()).isEqualTo(2);
    }

    @Test
    void extractsHomePitcherRecordsWithDisplayedOrder() {
        var result = parser.parse(fixtureHtml());

        assertThat(result.homePitchers()).hasSize(2);
        assertThat(result.homePitchers().get(0).playerName()).isEqualTo("전사민");
        assertThat(result.homePitchers().get(1).playerName()).isEqualTo("최준용");
        assertThat(result.homePitchers().get(1).strikeouts()).isEqualTo(3);
    }

    @Test
    void extractsPlayByPlayEventsAndMapsEventTypes() {
        var result = parser.parse(fixtureHtml());

        assertThat(result.events()).extracting(KboLiveTextParser.ParsedLiveTextEvent::eventText)
                .contains(
                        "김주원 : 삼진 아웃",
                        "박승욱 : 중견수 앞 1루타",
                        "나승엽 : 볼넷",
                        "투수 박세웅 : 투수 김원중 (으)로 교체"
                );
        assertThat(eventType(result, "김주원 : 삼진 아웃")).isEqualTo("STRIKEOUT");
        assertThat(eventType(result, "박승욱 : 중견수 앞 1루타")).isEqualTo("HIT");
        assertThat(eventType(result, "나승엽 : 볼넷")).isEqualTo("WALK");
        assertThat(eventType(result, "투수 박세웅 : 투수 김원중 (으)로 교체")).isEqualTo("PITCHING_CHANGE");
    }

    @Test
    void extractsEventsFromNumContSpansWithBrChildren() {
        var result = parser.parse(fixtureHtml());

        assertThat(result.eventCandidateCount()).isEqualTo(11);
        assertThat(result.skippedEventCount()).isEqualTo(3);
        assertThat(result.events()).hasSize(8);
        assertThat(result.events().get(0).inning()).isEqualTo(1);
        assertThat(result.events().get(0).inningHalf()).isEqualTo("top");
        assertThat(result.events().get(0).eventText()).isEqualTo("김주원 : 삼진 아웃");
    }

    @Test
    void persistsUnknownRawEventsInsteadOfDroppingThem() {
        var result = parser.parse(fixtureHtml());

        assertThat(eventType(result, "비디오 판독 후 원심 유지")).isEqualTo("UNKNOWN");
    }

    @Test
    void handlesEmptyOrMissingTablesGracefully() {
        var result = parser.parse("<html><body><div>no records</div></body></html>");

        assertThat(result.awayBatters()).isEmpty();
        assertThat(result.homeBatters()).isEmpty();
        assertThat(result.awayPitchers()).isEmpty();
        assertThat(result.homePitchers()).isEmpty();
        assertThat(result.events()).isEmpty();
    }

    private String eventType(KboLiveTextParser.ParsedLiveText result, String text) {
        return result.events().stream()
                .filter(event -> text.equals(event.eventText()))
                .findFirst()
                .orElseThrow()
                .eventType();
    }

    private String fixtureHtml() {
        return """
                <html>
                  <body>
                    <h2>박스스코어</h2>
                    <table>
                      <tr><th>타수</th><th>득점</th><th>안타</th><th>타점</th></tr>
                      <tr><td>요약타자</td><td>1</td><td>1</td><td>1</td></tr>
                    </table>

                    <h3>롯데 라인업</h3>
                    <table>
                      <tr><th>타순</th><th>포지션</th><th>타자</th><th>타수</th><th>득점</th><th>안타</th><th>홈런</th><th>타점</th><th>도루</th><th>희타</th><th>볼넷</th><th>삼진</th><th>병살</th><th>실책</th></tr>
                      <tr><td>1</td><td>유</td><td>박승욱</td><td>4</td><td>1</td><td>2</td><td>0</td><td>1</td><td>1</td><td>0</td><td>1</td><td>0</td><td>0</td><td>0</td></tr>
                      <tr><td>2</td><td>一</td><td>나승엽</td><td>3</td><td>0</td><td>1</td><td>0</td><td>0</td><td>0</td><td>0</td><td>2</td><td>1</td><td>0</td><td>0</td></tr>
                    </table>

                    <h3>NC 라인업</h3>
                    <table>
                      <tr><th>타순</th><th>포지션</th><th>타자</th><th>타수</th><th>득점</th><th>안타</th><th>홈런</th><th>타점</th><th>도루</th><th>희타</th><th>볼넷</th><th>삼진</th><th>병살</th><th>실책</th></tr>
                      <tr><td>1</td><td>유</td><td>김주원</td><td>5</td><td>2</td><td>3</td><td>1</td><td>4</td><td>0</td><td>0</td><td>2</td><td>1</td><td>1</td><td>1</td></tr>
                      <tr><td>2</td><td>중</td><td>박민우</td><td>4</td><td>1</td><td>1</td><td>0</td><td>1</td><td>1</td><td>1</td><td>0</td><td>0</td><td>0</td><td>0</td></tr>
                    </table>

                    <table>
                      <tr><th>투수</th><th>이닝</th><th>타자</th><th>투구</th><th>타수</th><th>안타</th><th>홈런</th><th>희타</th><th>볼넷</th><th>삼진</th><th>실점</th><th>자책</th></tr>
                      <tr><td>박세웅</td><td>6</td><td>24</td><td>88</td><td>22</td><td>5</td><td>0</td><td>1</td><td>1</td><td>6</td><td>1</td><td>1</td></tr>
                      <tr><td>김원중</td><td>1</td><td>3</td><td>12</td><td>3</td><td>0</td><td>0</td><td>0</td><td>0</td><td>1</td><td>0</td><td>0</td></tr>
                    </table>

                    <table>
                      <tr><th>투수</th><th>이닝</th><th>타자</th><th>투구</th><th>타수</th><th>안타</th><th>홈런</th><th>희타</th><th>볼넷</th><th>삼진</th><th>실점</th><th>자책</th></tr>
                      <tr><td>전사민</td><td>2</td><td>12</td><td>46</td><td>8</td><td>3</td><td>1</td><td>0</td><td>3</td><td>0</td><td>3</td><td>2</td></tr>
                      <tr><td>최준용</td><td>3</td><td>10</td><td>40</td><td>9</td><td>1</td><td>0</td><td>0</td><td>1</td><td>3</td><td>0</td><td>0</td></tr>
                    </table>

                    <div id="numCont1" class="numCon">
                      <span id="rptLiveText1_spanLiveText_0" class="blue">
                        1회초 롯데 공격<br />
                        ---------------------------------------
                      </span>
                      <span id="rptLiveText1_spanLiveText_1" class="normaiflTxt">김주원 : 삼진 아웃<br /></span>
                      <span id="rptLiveText1_spanLiveText_2" class="normalifLTxt">박승욱 : 중견수 앞 1루타<br /></span>
                      <span id="rptLiveText1_spanLiveText_3" class="normaiflTxt">나승엽 : 볼넷<br /></span>
                      <span id="rptLiveText1_spanLiveText_4" class="red">투수 박세웅 : 투수 김원중 (으)로 교체<br /></span>
                      <span id="rptLiveText1_spanLiveText_5" class="normaiflTxt">2루주자 박승욱 : 홈인<br /></span>
                      <span id="rptLiveText1_spanLiveText_6" class="normaiflTxt">비디오 판독 후 원심 유지<br /></span>
                      <span id="rptLiveText1_spanLiveText_7" class="normaiflTxt">- 1구 볼<br /></span>
                      <span id="rptLiveText1_spanLiveText_8" class="normaiflTxt">4번타자 나승엽<br /></span>
                      <span id="rptLiveText1_spanLiveText_9" class="blue">패전투수: 전사민<br /></span>
                      <span id="rptLiveText1_spanLiveText_10" class="blue">승리투수: 최준용<br /></span>
                    </div>
                  </body>
                </html>
                """;
    }
}
