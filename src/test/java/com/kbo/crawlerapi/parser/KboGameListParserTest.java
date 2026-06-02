package com.kbo.crawlerapi.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

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
}
