package com.kbo.crawlerapi.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;

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
}
