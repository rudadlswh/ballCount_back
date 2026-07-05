package com.kbo.crawlerapi.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.GameStatus;

class KboGameListParserTest {

    private final KboGameListParser parser = new KboGameListParser(new ObjectMapper());

    @Test
    void extractsStartingPitcherNamesFromOfficialGameListPayload() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260602LTHT0",
                      "AWAY_NM": "롯데",
                      "HOME_NM": "KIA",
                      "T_PIT_P_NM": "나균안",
                      "B_PIT_P_NM": "네일"
                    }
                  ]
                }
                """;

        var games = parser.parseGameList(payload);

        assertThat(games).hasSize(1);
        assertThat(games.get(0).providerGameId()).isEqualTo("20260602LTHT0");
        assertThat(games.get(0).awayStartingPitcherName()).isEqualTo("나균안");
        assertThat(games.get(0).homeStartingPitcherName()).isEqualTo("네일");
    }

    @Test
    void trimsStartingPitcherNames() {
        String payload = """
                {
                  "d": "{\\"game\\":[{\\"G_ID\\":\\"20260602LTHT0\\",\\"T_PIT_P_NM\\":\\"나균안  \\",\\"B_PIT_P_NM\\":\\"  네일\\"}]}"
                }
                """;

        var games = parser.parseGameList(payload);

        assertThat(games).hasSize(1);
        assertThat(games.get(0).awayStartingPitcherName()).isEqualTo("나균안");
        assertThat(games.get(0).homeStartingPitcherName()).isEqualTo("네일");
    }

    @Test
    void extractsDelayStatusReasonFromOfficialGameListPayload() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260702OBLT0",
                      "AWAY_NM": "롯데",
                      "HOME_NM": "두산",
                      "GAME_STATE_SC_NM": "우천 지연"
                    }
                  ]
                }
                """;

        var games = parser.parseGameList(payload);

        assertThat(games).hasSize(1);
        assertThat(games.get(0).status()).isEqualTo(GameStatus.DELAYED);
        assertThat(games.get(0).statusReason()).isEqualTo("우천 지연");
    }

    @Test
    void extractsRainCancellationStatusFromOfficialGameListPayload() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260705LGHH0",
                      "AWAY_NM": "한화",
                      "HOME_NM": "LG",
                      "CANCEL_SC_NM": "우천취소"
                    }
                  ]
                }
                """;

        var games = parser.parseGameList(payload);

        assertThat(games).hasSize(1);
        assertThat(games.get(0).providerGameId()).isEqualTo("20260705LGHH0");
        assertThat(games.get(0).status()).isEqualTo(GameStatus.CANCELLED);
        assertThat(games.get(0).statusReason()).isEqualTo("우천취소");
    }

    @Test
    void extractsPostponedStatusFromOfficialGameListPayload() {
        String payload = """
                {
                  "game": [
                    {
                      "G_ID": "20260705NCHT0",
                      "AWAY_NM": "NC",
                      "HOME_NM": "KIA",
                      "GAME_STATE_SC_NM": "POSTPONED"
                    }
                  ]
                }
                """;

        var games = parser.parseGameList(payload);

        assertThat(games).hasSize(1);
        assertThat(games.get(0).status()).isEqualTo(GameStatus.POSTPONED);
        assertThat(games.get(0).statusReason()).isEqualTo("POSTPONED");
    }
}
