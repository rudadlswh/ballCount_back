package com.kbo.crawlerapi.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;

class KboGameDetailParserTest {

    private final KboGameDetailParser parser = new KboGameDetailParser(new ObjectMapper());

    @Test
    void scoreBoardRainInterruptionMapsToSuspended() {
        String html = """
                <html>
                  <body>
                    <div class="scoreboard">
                      <span class="status">우천중단</span>
                    </div>
                  </body>
                </html>
                """;

        var status = parser.parseScoreBoardStatus("20260526LGLT0", html);

        assertThat(status).isPresent();
        assertThat(status.get().status()).isEqualTo(GameStatus.SUSPENDED);
        assertThat(status.get().statusReason()).isEqualTo("우천중단");
    }

    @Test
    void scoreBoardRainDelayMapsToSuspended() {
        String html = """
                <html>
                  <body>
                    <div class="scoreboard">
                      <span class="status">우천 지연</span>
                    </div>
                  </body>
                </html>
                """;

        var status = parser.parseScoreBoardStatus("20260625NCLT0", html);

        assertThat(status).isPresent();
        assertThat(status.get().status()).isEqualTo(GameStatus.SUSPENDED);
        assertThat(status.get().statusReason()).isEqualTo("우천 지연");
    }

    @Test
    void scoreBoardErrorPageIsIgnored() {
        String html = """
                <html>
                  <body>Object moved Object moved to here. 200 입력 문자열의 형식이 잘못되었습니다.</body>
                </html>
                """;

        var status = parser.parseScoreBoardStatus("20260526LGLT0", html);

        assertThat(status).isEmpty();
    }

    @Test
    void rawStatusCodeThreeWithoutFinalMarkerDoesNotParseAsFinal() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260526LGLT0",
                      "GAME_STATE_SC": "3",
                      "GAME_RESULT_CK": 0,
                      "GAME_SC_NM": "정규경기",
                      "CANCEL_SC_NM": "정상경기",
                      "GAME_INN_NO": 8,
                      "GAME_TB_SC": "T",
                      "SCORE_CK": "1",
                      "T_SCORE_CN": "2",
                      "B_SCORE_CN": "1"
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(GameStatus.LIVE);
        assertThat(result.get(0).statusReason()).isNull();
    }

    @Test
    void gameListRainDelayTextWinsOverLiveGameState() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260625NCLT0",
                      "GAME_STATE_SC": "2",
                      "GAME_STATE_SC_NM": "우천 지연",
                      "GAME_INN_NO": 1,
                      "GAME_TB_SC": "T",
                      "SCORE_CK": "0",
                      "T_SCORE_CN": "0",
                      "B_SCORE_CN": "0"
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(GameStatus.SUSPENDED);
        assertThat(result.get(0).statusReason()).isEqualTo("우천 지연");
    }

    @Test
    void parsesFinalGameSnapshotFields() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260401HTLG0",
                      "GAME_STATE_SC": "3",
                      "GAME_RESULT_CK": 1,
                      "CANCEL_SC_NM": "정상경기",
                      "GAME_INN_NO": 9,
                      "GAME_TB_SC": "T",
                      "SCORE_CK": "1",
                      "T_SCORE_CN": "2",
                      "B_SCORE_CN": "7",
                      "BALL_CN": 0,
                      "STRIKE_CN": 0,
                      "OUT_CN": 3,
                      "B1_BAT_ORDER_NO": 8,
                      "B2_BAT_ORDER_NO": 7,
                      "B3_BAT_ORDER_NO": 6
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).providerGameId()).isEqualTo("20260401HTLG0");
        assertThat(result.get(0).status().getApiValue()).isEqualTo("final");
        assertThat(result.get(0).inning()).isEqualTo(9);
        assertThat(result.get(0).inningHalf()).isEqualTo("top");
        assertThat(result.get(0).inningLabel()).isEqualTo("Top 9");
        assertThat(result.get(0).awayScore()).isEqualTo(2);
        assertThat(result.get(0).homeScore()).isEqualTo(7);
        assertThat(result.get(0).runnerOnFirst()).isTrue();
        assertThat(result.get(0).cancelReason()).isNull();
    }

    @Test
    void parsesGameRowsFromAsmxDStringWrapper() {
        String payload = """
                {
                  "d": "{\\"game\\":[{\\"G_ID\\":\\"20260521HHLT0\\",\\"GAME_STATE_SC\\":\\"2\\",\\"GAME_INN_NO\\":3,\\"GAME_TB_SC\\":\\"B\\",\\"SCORE_CK\\":\\"1\\",\\"T_SCORE_CN\\":\\"1\\",\\"B_SCORE_CN\\":\\"2\\",\\"BALL_CN\\":1,\\"STRIKE_CN\\":2,\\"OUT_CN\\":0}]}"
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).providerGameId()).isEqualTo("20260521HHLT0");
        assertThat(result.get(0).status().getApiValue()).isEqualTo("live");
        assertThat(result.get(0).inning()).isEqualTo(3);
        assertThat(result.get(0).inningHalf()).isEqualTo("bottom");
    }

    @Test
    void parsesGameRowsFromDataGamesShape() {
        String payload = """
                {
                  "data": {
                    "games": [
                      {
                        "G_ID": "20260521HHLT0",
                        "GAME_STATE_SC": "2",
                        "GAME_INN_NO": 1,
                        "GAME_TB_SC": "T",
                        "SCORE_CK": "1",
                        "T_SCORE_CN": "0",
                        "B_SCORE_CN": "0"
                      }
                    ]
                  }
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).providerGameId()).isEqualTo("20260521HHLT0");
    }

    @Test
    void parsesOccupiedFirstBaseRunnerNameAndId() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260401HTLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_INN_NO": 3,
                      "GAME_TB_SC": "T",
                      "B1_BAT_ORDER_NO": 2,
                      "B1_RUNNER_NM": "홍길동",
                      "B1_RUNNER_ID": "runner-1",
                      "B2_BAT_ORDER_NO": 0,
                      "B3_BAT_ORDER_NO": 0
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).runnerOnFirst()).isTrue();
        assertThat(result.get(0).firstBaseRunnerName()).isEqualTo("홍길동");
        assertThat(result.get(0).firstBaseRunnerId()).isEqualTo("runner-1");
        assertThat(result.get(0).secondBaseRunnerName()).isNull();
        assertThat(result.get(0).thirdBaseRunnerName()).isNull();
    }

    @Test
    void parsesMultipleOccupiedBaseRunnerNames() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260401HTLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_INN_NO": 5,
                      "GAME_TB_SC": "B",
                      "B1_BAT_ORDER_NO": 1,
                      "B1_P_NM": "1루주자",
                      "B2_BAT_ORDER_NO": 4,
                      "B2_PLAYER_NM": "2루주자",
                      "B3_BAT_ORDER_NO": 8,
                      "B3_NAME": "3루주자"
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).runnerOnFirst()).isTrue();
        assertThat(result.get(0).runnerOnSecond()).isTrue();
        assertThat(result.get(0).runnerOnThird()).isTrue();
        assertThat(result.get(0).firstBaseRunnerName()).isEqualTo("1루주자");
        assertThat(result.get(0).secondBaseRunnerName()).isEqualTo("2루주자");
        assertThat(result.get(0).thirdBaseRunnerName()).isEqualTo("3루주자");
    }

    @Test
    void leavesRunnerNamesNilForEmptyBasesOrMissingOfficialFields() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260401HTLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_INN_NO": 6,
                      "GAME_TB_SC": "T",
                      "B1_BAT_ORDER_NO": 0,
                      "B2_BAT_ORDER_NO": 0,
                      "B3_BAT_ORDER_NO": 0
                    },
                    {
                      "G_ID": "20260401HTLG1",
                      "GAME_STATE_SC": "2",
                      "GAME_INN_NO": 6,
                      "GAME_TB_SC": "T",
                      "B1_BAT_ORDER_NO": 3
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).runnerOnFirst()).isFalse();
        assertThat(result.get(0).firstBaseRunnerName()).isNull();
        assertThat(result.get(0).secondBaseRunnerName()).isNull();
        assertThat(result.get(0).thirdBaseRunnerName()).isNull();
        assertThat(result.get(1).runnerOnFirst()).isTrue();
        assertThat(result.get(1).firstBaseRunnerName()).isNull();
    }

    @Test
    void keepsScheduledScoresNullable() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260409SKLG0",
                      "GAME_STATE_SC": "1",
                      "GAME_RESULT_CK": 0,
                      "CANCEL_SC_NM": "정상경기",
                      "GAME_INN_NO": null,
                      "GAME_TB_SC": null,
                      "SCORE_CK": "0",
                      "T_SCORE_CN": "0",
                      "B_SCORE_CN": "0"
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status().getApiValue()).isEqualTo("scheduled");
        assertThat(result.get(0).awayScore()).isNull();
        assertThat(result.get(0).homeScore()).isNull();
        assertThat(result.get(0).inning()).isNull();
        assertThat(result.get(0).cancelReason()).isNull();
    }

    @Test
    void mapsRainInterruptedStatusToSuspendedWithoutCancellationFlags() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260526LTLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_STATE_SC_NM": "우천중단",
                      "CANCEL_SC_NM": "정상경기",
                      "GAME_INN_NO": 8,
                      "GAME_TB_SC": "T",
                      "SCORE_CK": "1",
                      "T_SCORE_CN": "1",
                      "B_SCORE_CN": "2"
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status().getApiValue()).isEqualTo("suspended");
        assertThat(result.get(0).statusReason()).isEqualTo("우천중단");
        assertThat(result.get(0).isCancelled()).isFalse();
        assertThat(result.get(0).isPostponed()).isFalse();
        assertThat(result.get(0).rawCancelText()).isNull();
        assertThat(result.get(0).cancelReason()).isNull();
        assertThat(result.get(0).awayScore()).isEqualTo(1);
        assertThat(result.get(0).homeScore()).isEqualTo(2);
        assertThat(result.get(0).inningLabel()).isEqualTo("Top 8");
    }

    @Test
    void parsesCurrentPitcherAndBatterFromOfficialTeamSideFieldsForTopHalf() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260409SKLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_RESULT_CK": 0,
                      "CANCEL_SC_NM": "정상경기",
                      "GAME_INN_NO": 4,
                      "GAME_TB_SC": "T",
                      "SCORE_CK": "1",
                      "T_SCORE_CN": "2",
                      "B_SCORE_CN": "3",
                      "BALL_CN": 1,
                      "STRIKE_CN": 2,
                      "OUT_CN": 1,
                      "T_PIT_P_NM": "원정선발",
                      "B_PIT_P_NM": "홈선발",
                      "T_P_NM": "원정타자",
                      "B_P_NM": "홈투수"
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currentPitcherName()).isEqualTo("홈투수");
        assertThat(result.get(0).currentBatterName()).isEqualTo("원정타자");
        assertThat(result.get(0).homeStartingPitcherName()).isEqualTo("홈선발");
        assertThat(result.get(0).awayStartingPitcherName()).isEqualTo("원정선발");
    }

    @Test
    void parsesCurrentPitcherAndBatterFromOfficialTeamSideFieldsForBottomHalf() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260409SKLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_RESULT_CK": 0,
                      "CANCEL_SC_NM": "정상경기",
                      "GAME_INN_NO": 4,
                      "GAME_TB_SC": "B",
                      "SCORE_CK": "1",
                      "T_SCORE_CN": "2",
                      "B_SCORE_CN": "3",
                      "T_P_NM": "원정투수",
                      "B_P_NM": "홈타자"
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currentPitcherName()).isEqualTo("원정투수");
        assertThat(result.get(0).currentBatterName()).isEqualTo("홈타자");
    }

    @Test
    void doesNotUseGuessedOrStartingPitcherFieldsForCurrentPitcherAndBatter() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260409SKLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_RESULT_CK": 0,
                      "GAME_INN_NO": 4,
                      "GAME_TB_SC": "B",
                      "T_PIT_P_NM": "원정선발",
                      "B_PIT_P_NM": "홈선발",
                      "PIT_P_NM": "현재투수",
                      "BAT_P_NM": "현재타자"
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currentPitcherName()).isNull();
        assertThat(result.get(0).currentBatterName()).isNull();
    }

    @Test
    void parsesCancelledGamesWithRainReason() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260409SSHT0",
                      "GAME_STATE_SC": "1",
                      "GAME_RESULT_CK": 0,
                      "CANCEL_SC_NM": "\\uC6B0\\uCC9C\\uCDE8\\uC18C",
                      "SCORE_CK": "0",
                      "T_SCORE_CN": "0",
                      "B_SCORE_CN": "0"
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status().getApiValue()).isEqualTo("cancelled");
        assertThat(result.get(0).cancelReason()).isEqualTo(GameCancelReason.RAIN);
        assertThat(result.get(0).rawCancelText()).isEqualTo("우천취소");
    }

    @Test
    void keepsLiveWhenNinthInningScoreLooksCompleteWithoutOfficialFinalMarker() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260423HHLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_RESULT_CK": 0,
                      "CANCEL_SC_NM": "정상경기",
                      "GAME_INN_NO": 9,
                      "GAME_TB_SC": "B",
                      "SCORE_CK": "1",
                      "T_SCORE_CN": "4",
                      "B_SCORE_CN": "5",
                      "BALL_CN": 0,
                      "STRIKE_CN": 0,
                      "OUT_CN": 1
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status().getApiValue()).isEqualTo("live");
        assertThat(result.get(0).awayScore()).isEqualTo(4);
        assertThat(result.get(0).homeScore()).isEqualTo(5);
    }

    @Test
    void keepsLiveWhenNinthInningHasThreeOutsWithoutOfficialFinalMarker() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260423HHLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_RESULT_CK": 0,
                      "CANCEL_SC_NM": "정상경기",
                      "GAME_INN_NO": 9,
                      "GAME_TB_SC": "T",
                      "SCORE_CK": "1",
                      "T_SCORE_CN": "4",
                      "B_SCORE_CN": "5",
                      "BALL_CN": 0,
                      "STRIKE_CN": 0,
                      "OUT_CN": 3
                    },
                    {
                      "G_ID": "20260423KTLT0",
                      "GAME_STATE_SC": "2",
                      "GAME_RESULT_CK": 0,
                      "CANCEL_SC_NM": "정상경기",
                      "GAME_INN_NO": 9,
                      "GAME_TB_SC": "B",
                      "SCORE_CK": "1",
                      "T_SCORE_CN": "6",
                      "B_SCORE_CN": "4",
                      "BALL_CN": 0,
                      "STRIKE_CN": 0,
                      "OUT_CN": 3
                    }
                  ]
                }
                """;

        var result = parser.parseGameList(payload);

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(detail -> detail.status().getApiValue())
                .containsExactly("live", "live");
    }

    @Test
    void mapsFirstBaseRunnerBattingOrderToAwayLineupInTopHalf() {
        var detail = parseRunnerState("T", 2, 0, 0);
        var enriched = parser.applyOfficialRunnerNamesFromLineup(detail, lineupData());

        assertThat(enriched.firstBaseRunnerName()).isEqualTo("원정2번");
        assertThat(enriched.secondBaseRunnerName()).isNull();
        assertThat(enriched.thirdBaseRunnerName()).isNull();
    }

    @Test
    void mapsSecondBaseRunnerBattingOrderToAwayLineupInTopHalf() {
        var detail = parseRunnerState("T", 0, 3, 0);
        var enriched = parser.applyOfficialRunnerNamesFromLineup(detail, lineupData());

        assertThat(enriched.firstBaseRunnerName()).isNull();
        assertThat(enriched.secondBaseRunnerName()).isEqualTo("원정3번");
        assertThat(enriched.thirdBaseRunnerName()).isNull();
    }

    @Test
    void mapsThirdBaseRunnerBattingOrderToAwayLineupInTopHalf() {
        var detail = parseRunnerState("T", 0, 0, 4);
        var enriched = parser.applyOfficialRunnerNamesFromLineup(detail, lineupData());

        assertThat(enriched.firstBaseRunnerName()).isNull();
        assertThat(enriched.secondBaseRunnerName()).isNull();
        assertThat(enriched.thirdBaseRunnerName()).isEqualTo("원정4번");
    }

    @Test
    void mapsFirstAndThirdBaseRunnerBattingOrdersToAwayLineupInTopHalf() {
        var detail = parseRunnerState("T", 2, 0, 4);
        var enriched = parser.applyOfficialRunnerNamesFromLineup(detail, lineupData());

        assertThat(enriched.firstBaseRunnerName()).isEqualTo("원정2번");
        assertThat(enriched.secondBaseRunnerName()).isNull();
        assertThat(enriched.thirdBaseRunnerName()).isEqualTo("원정4번");
    }

    @Test
    void mapsFirstAndSecondBaseRunnerBattingOrdersToHomeLineupInBottomHalf() {
        var detail = parseRunnerState("B", 5, 6, 0);
        var enriched = parser.applyOfficialRunnerNamesFromLineup(detail, lineupData());

        assertThat(enriched.firstBaseRunnerName()).isEqualTo("홈5번");
        assertThat(enriched.secondBaseRunnerName()).isEqualTo("홈6번");
        assertThat(enriched.thirdBaseRunnerName()).isNull();
    }

    @Test
    void mapsLoadedBaseRunnerBattingOrdersToHomeLineupInBottomHalf() {
        var detail = parseRunnerState("B", 5, 6, 7);
        var enriched = parser.applyOfficialRunnerNamesFromLineup(detail, lineupData());

        assertThat(enriched.firstBaseRunnerName()).isEqualTo("홈5번");
        assertThat(enriched.secondBaseRunnerName()).isEqualTo("홈6번");
        assertThat(enriched.thirdBaseRunnerName()).isEqualTo("홈7번");
    }

    @Test
    void directOfficialRunnerNameWinsOverLineupMappedName() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260401HTLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_INN_NO": 3,
                      "GAME_TB_SC": "T",
                      "B1_BAT_ORDER_NO": 2,
                      "B1_RUNNER_NM": "공식1루주자",
                      "B2_BAT_ORDER_NO": 0,
                      "B3_BAT_ORDER_NO": 0
                    }
                  ]
                }
                """;
        var detail = parser.parseGameList(payload).get(0);

        var enriched = parser.applyOfficialRunnerNamesFromLineup(detail, lineupData());

        assertThat(enriched.firstBaseRunnerName()).isEqualTo("공식1루주자");
    }

    @Test
    void parsesEquivalentBaseBattingOrderFieldNames() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260401HTLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_INN_NO": 3,
                      "GAME_TB_SC": "T",
                      "B1BATORDERNO": 2,
                      "BASE2_BAT_ORDER_NO": 3,
                      "BASE3BATORDERNO": 4
                    }
                  ]
                }
                """;
        var detail = parser.parseGameList(payload).get(0);

        assertThat(detail.runnerOnFirst()).isTrue();
        assertThat(detail.runnerOnSecond()).isTrue();
        assertThat(detail.runnerOnThird()).isTrue();
        assertThat(detail.firstBaseBattingOrder()).isEqualTo(2);
        assertThat(detail.secondBaseBattingOrder()).isEqualTo(3);
        assertThat(detail.thirdBaseBattingOrder()).isEqualTo(4);
    }

    @Test
    void parsesOfficialLineupPositionsAsEnglishCodes() {
        String payload = """
                {
                  "AWAY_ID": "LT",
                  "HOME_ID": "SK",
                  "arrHitter": [
                    { "table1": "%s" },
                    { "table1": "%s" }
                  ]
                }
                """.formatted(
                        escapedLineupTable(
                                row("1", "8", "황성빈"),
                                row("2", "4", "고승민"),
                                row("3", "9", "레이예스"),
                                row("4", "5", "한동희"),
                                row("5", "3", "나승엽")
                        ),
                        escapedLineupTable(
                                row("1", "6", "박성한"),
                                row("2", "7", "에레디아"),
                                row("3", "2", "조형우"),
                                row("4", "D", "최정")
                        )
                );

        var lineup = parser.parseLineupData(payload);

        assertThat(lineup.awayTeamCode()).isEqualTo("LT");
        assertThat(lineup.homeTeamCode()).isEqualTo("SK");
        assertThat(lineup.away()).extracting(KboGameDetailParser.ParsedLineupPlayer::position)
                .containsExactly("CF", "2B", "RF", "3B", "1B");
        assertThat(lineup.home()).extracting(KboGameDetailParser.ParsedLineupPlayer::position)
                .containsExactly("SS", "LF", "C", "DH");
    }

    private KboGameDetailParser.ParsedGameDetail parseRunnerState(String officialHalf, int firstOrder, int secondOrder, int thirdOrder) {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260401HTLG0",
                      "GAME_STATE_SC": "2",
                      "GAME_INN_NO": 3,
                      "GAME_TB_SC": "%s",
                      "B1_BAT_ORDER_NO": %d,
                      "B2_BAT_ORDER_NO": %d,
                      "B3_BAT_ORDER_NO": %d
                    }
                  ]
                }
                """.formatted(officialHalf, firstOrder, secondOrder, thirdOrder);
        return parser.parseGameList(payload).get(0);
    }

    private String row(String battingOrder, String position, String name) {
        return """
                {
                  "row": [
                    { "Text": "%s" },
                    { "Text": "%s" },
                    { "Text": "%s" }
                  ]
                }
                """.formatted(battingOrder, position, name);
    }

    private String escapedLineupTable(String... rows) {
        String table = """
                {
                  "rows": [
                    %s
                  ]
                }
                """.formatted(String.join(",", rows));
        return table.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\r\\n");
    }

    private KboGameDetailParser.ParsedLineupData lineupData() {
        return new KboGameDetailParser.ParsedLineupData(
                List.of(
                        new KboGameDetailParser.ParsedLineupPlayer("1", "CF", "원정1번"),
                        new KboGameDetailParser.ParsedLineupPlayer("2", "SS", "원정2번"),
                        new KboGameDetailParser.ParsedLineupPlayer("3", "RF", "원정3번"),
                        new KboGameDetailParser.ParsedLineupPlayer("4", "1B", "원정4번")
                ),
                List.of(
                        new KboGameDetailParser.ParsedLineupPlayer("5", "DH", "홈5번"),
                        new KboGameDetailParser.ParsedLineupPlayer("6", "LF", "홈6번"),
                        new KboGameDetailParser.ParsedLineupPlayer("7", "2B", "홈7번")
                ),
                "lineup-hash"
        );
    }
}
