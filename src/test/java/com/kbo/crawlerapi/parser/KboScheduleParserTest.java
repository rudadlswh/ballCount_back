package com.kbo.crawlerapi.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;

@ExtendWith(OutputCaptureExtension.class)
class KboScheduleParserTest {

    private final KboScheduleParser parser = new KboScheduleParser(new ObjectMapper());

    @Test
    void parsesScheduledAndFinalGamesFromOfficialSchedulePayload() {
        String payload = """
                {
                  "rows": [
                    {
                      "row": [
                        { "Text": "04.09(\\uBAA9)" },
                        { "Text": "<b>18:30</b>" },
                        { "Text": "<span>\\uD0A4\\uC6C0</span><em><span>vs</span></em><span>\\uB450\\uC0B0</span>" },
                        { "Text": "<a href='/Schedule/GameCenter/Main.aspx?gameDate=20260409&gameId=20260409WOOB0&section=START_PIT' class='btn2' id='btnPreView'>\\uD504\\uB9AC\\uBDF0</a>" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "\\uC7A0\\uC2E4" },
                        { "Text": "-" }
                      ]
                    },
                    {
                      "row": [
                        { "Text": "04.01(\\uC218)" },
                        { "Text": "<b>18:30</b>" },
                        { "Text": "<span>KIA</span><em><span class=\\"lose\\">2</span><span>vs</span><span class=\\"win\\">7</span></em><span>LG</span>" },
                        { "Text": "<a href='/Schedule/GameCenter/Main.aspx?gameDate=20260401&gameId=20260401HTLG0&section=REVIEW' class='btn2 mr5' id='btnReview'>\\uB9AC\\uBDF0</a>" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "\\uC7A0\\uC2E4" },
                        { "Text": "-" }
                      ]
                    }
                  ]
                }
                """;

        var games = parser.parseMonthlySchedule(payload, YearMonth.of(2026, 4));

        assertThat(games).hasSize(2);
        assertThat(games.get(0).providerGameId()).isEqualTo("20260409WOOB0");
        assertThat(games.get(0).awayProviderTeamName()).isEqualTo("키움");
        assertThat(games.get(0).homeProviderTeamName()).isEqualTo("두산");
        assertThat(games.get(0).status()).isEqualTo(GameStatus.SCHEDULED);
        assertThat(games.get(0).awayScore()).isNull();
        assertThat(games.get(1).status()).isEqualTo(GameStatus.FINAL);
        assertThat(games.get(1).awayScore()).isEqualTo(2);
        assertThat(games.get(1).homeScore()).isEqualTo(7);
        assertThat(games.get(0).cancelReason()).isNull();
    }

    @Test
    void parsesCancelledGamesWithKnownAndUnknownCancelReasons() {
        String payload = """
                {
                  "rows": [
                    {
                      "row": [
                        { "Text": "04.09(\\uBAA9)" },
                        { "Text": "<b>18:30</b>" },
                        { "Text": "<span>\\uC0BC\\uC131</span><em><span>vs</span></em><span>KIA</span>" },
                        { "Text": "<a href='/Schedule/GameCenter/Main.aspx?gameDate=20260409&gameId=20260409SSHT0&section=PREVIEW' class='btn2'>\\uD504\\uB9AC\\uBDF0</a>" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "\\uAD11\\uC8FC" },
                        { "Text": "\\uC6B0\\uCC9C\\uCDE8\\uC18C" }
                      ]
                    },
                    {
                      "row": [
                        { "Text": "04.10(\\uAE08)" },
                        { "Text": "<b>18:30</b>" },
                        { "Text": "<span>LG</span><em><span>vs</span></em><span>SSG</span>" },
                        { "Text": "<a href='/Schedule/GameCenter/Main.aspx?gameDate=20260410&gameId=20260410LGSK0&section=PREVIEW' class='btn2'>\\uD504\\uB9AC\\uBDF0</a>" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "\\uC7A0\\uC2E4" },
                        { "Text": "\\uCDE8\\uC18C" }
                      ]
                    }
                  ]
                }
                """;

        var games = parser.parseMonthlySchedule(payload, YearMonth.of(2026, 4));

        assertThat(games).hasSize(2);
        assertThat(games.get(0).status()).isEqualTo(GameStatus.CANCELLED);
        assertThat(games.get(0).cancelReason()).isEqualTo(GameCancelReason.RAIN);
        assertThat(games.get(0).rawCancelText()).isEqualTo("우천취소");
        assertThat(games.get(1).status()).isEqualTo(GameStatus.CANCELLED);
        assertThat(games.get(1).cancelReason()).isEqualTo(GameCancelReason.UNKNOWN);
        assertThat(games.get(1).rawCancelText()).isEqualTo("취소");
    }

    @Test
    void missingProviderGameIdRowsAreReturnedWithoutWarnLogging(CapturedOutput output) {
        String payload = """
                {
                  "rows": [
                    {
                      "row": [
                        { "Text": "05.20(\\uC218)" },
                        { "Text": "<b>18:30</b>" },
                        { "Text": "<span>NC</span><em><span>vs</span></em><span>두산</span>" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "잠실" },
                        { "Text": "우천취소" }
                      ]
                    }
                  ]
                }
                """;

        var result = parser.parseMonthlyScheduleResult(payload, YearMonth.of(2026, 5));

        assertThat(result.skippedRows()).hasSize(1);
        assertThat(result.skippedRows().get(0).reason()).isEqualTo("MISSING_PROVIDER_GAME_ID");
        assertThat(output.getOut()).doesNotContain("WARN");
        assertThat(output.getOut()).doesNotContain("Skipped malformed KBO schedule row");
    }

    @Test
    void parsesCancelledScheduleRowFromDataGamesShape() {
        String payload = """
                {
                  "data": {
                    "games": [
                      {
                        "G_ID": "20260521HHLT0",
                        "G_DT": "20260521",
                        "G_TM": "18:30",
                        "AWAY_NM": "한화",
                        "HOME_NM": "롯데",
                        "S_NM": "사직",
                        "CANCEL_SC_NM": "경기취소"
                      }
                    ]
                  }
                }
                """;

        var games = parser.parseMonthlySchedule(payload, YearMonth.of(2026, 5));

        assertThat(games).hasSize(1);
        assertThat(games.get(0).providerGameId()).isEqualTo("20260521HHLT0");
        assertThat(games.get(0).status()).isEqualTo(GameStatus.CANCELLED);
        assertThat(games.get(0).isCancelled()).isTrue();
        assertThat(games.get(0).awayProviderTeamName()).isEqualTo("한화");
        assertThat(games.get(0).homeProviderTeamName()).isEqualTo("롯데");
    }

    @Test
    void parsesStartingPitcherNamesFromOfficialScheduleObjectRow() {
        String payload = """
                {
                  "data": {
                    "games": [
                      {
                        "G_ID": "20260602LTHT0",
                        "G_DT": "20260602",
                        "G_TM": "18:30",
                        "AWAY_NM": "KIA",
                        "HOME_NM": "롯데",
                        "S_NM": "사직",
                        "T_PIT_P_NM": "원정선발",
                        "B_PIT_P_NM": "홈선발"
                      }
                    ]
                  }
                }
                """;

        var games = parser.parseMonthlySchedule(payload, YearMonth.of(2026, 6));

        assertThat(games).hasSize(1);
        assertThat(games.get(0).providerGameId()).isEqualTo("20260602LTHT0");
        assertThat(games.get(0).awayStartingPitcherName()).isEqualTo("원정선발");
        assertThat(games.get(0).homeStartingPitcherName()).isEqualTo("홈선발");
    }

    @Test
    void parsesRainCancelledScheduleRowAndPreservesRawCancelText() {
        String payload = """
                {
                  "gameList": [
                    {
                      "G_ID": "20260521HHLT0",
                      "G_DT": "2026-05-21",
                      "G_TM": "18:30",
                      "AWAY_ID": "HH",
                      "HOME_ID": "LT",
                      "S_NM": "사직",
                      "GAME_STATE_SC_NM": "RAIN"
                    }
                  ]
                }
                """;

        var games = parser.parseMonthlySchedule(payload, YearMonth.of(2026, 5));

        assertThat(games).hasSize(1);
        assertThat(games.get(0).status()).isEqualTo(GameStatus.CANCELLED);
        assertThat(games.get(0).cancelReason()).isEqualTo(GameCancelReason.RAIN);
        assertThat(games.get(0).rawCancelText()).isEqualTo("RAIN");
        assertThat(games.get(0).awayProviderTeamName()).isEqualTo("한화");
        assertThat(games.get(0).homeProviderTeamName()).isEqualTo("롯데");
    }

    @Test
    void parsesRainDelayScheduleRowAsSuspendedNotCancelled() {
        String payload = """
                {
                  "gameList": [
                    {
                      "G_ID": "20260526LTLG0",
                      "G_DT": "2026-05-26",
                      "G_TM": "18:30",
                      "AWAY_ID": "LT",
                      "HOME_ID": "LG",
                      "S_NM": "잠실",
                      "GAME_STATE_SC_NM": "rain delay",
                      "AWAY_SCORE": "1",
                      "HOME_SCORE": "2"
                    }
                  ]
                }
                """;

        var games = parser.parseMonthlySchedule(payload, YearMonth.of(2026, 5));

        assertThat(games).hasSize(1);
        assertThat(games.get(0).status()).isEqualTo(GameStatus.SUSPENDED);
        assertThat(games.get(0).isCancelled()).isFalse();
        assertThat(games.get(0).isPostponed()).isFalse();
        assertThat(games.get(0).rawCancelText()).isNull();
        assertThat(games.get(0).awayScore()).isEqualTo(1);
        assertThat(games.get(0).homeScore()).isEqualTo(2);
    }

    @Test
    void skipsMalformedRowsWhileKeepingValidRows() {
        String payload = """
                {
                  "data": {
                    "list": [
                      { "G_DT": "20260521", "AWAY_NM": "한화" },
                      {
                        "G_ID": "20260521HHLT0",
                        "G_DT": "20260521",
                        "G_TM": "18:30",
                        "AWAY_NM": "한화",
                        "HOME_NM": "롯데",
                        "S_NM": "사직",
                        "CANCEL_SC_NM": "우천취소"
                      }
                    ]
                  }
                }
                """;

        var result = parser.parseMonthlyScheduleResult(payload, YearMonth.of(2026, 5));

        assertThat(result.games()).hasSize(1);
        assertThat(result.games().get(0).status()).isEqualTo(GameStatus.CANCELLED);
        assertThat(result.skippedRows()).hasSize(1);
        assertThat(result.skippedRows().get(0).reason()).isEqualTo("MISSING_TEAM");
    }
}
