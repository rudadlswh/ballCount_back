package com.kbo.crawlerapi.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.GameCancelReason;

class KboGameDetailParserTest {

    private final KboGameDetailParser parser = new KboGameDetailParser(new ObjectMapper());

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
    void infersFinalWhenOfficialPayloadShowsGameOverDespiteLiveState() {
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
        assertThat(result.get(0).status().getApiValue()).isEqualTo("final");
        assertThat(result.get(0).awayScore()).isEqualTo(4);
        assertThat(result.get(0).homeScore()).isEqualTo(5);
    }
}
