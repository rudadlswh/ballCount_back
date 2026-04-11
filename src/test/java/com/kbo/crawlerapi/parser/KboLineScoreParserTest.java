package com.kbo.crawlerapi.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

class KboLineScoreParserTest {

    private final KboLineScoreParser parser = new KboLineScoreParser(new ObjectMapper());

    @Test
    void parsesFinalGameLineScoresAndTotals() {
        String payload = """
                {
                  "code": "100",
                  "table2": "{\\"headers\\":[{\\"row\\":[{\\"Text\\":\\"1\\"},{\\"Text\\":\\"2\\"},{\\"Text\\":\\"3\\"}]}],\\"rows\\":[{\\"row\\":[{\\"Text\\":\\"0\\"},{\\"Text\\":\\"1\\"},{\\"Text\\":\\"-\\"}]},{\\"row\\":[{\\"Text\\":\\"3\\"},{\\"Text\\":\\"0\\"},{\\"Text\\":\\"-\\"}]}]}",
                  "table3": "{\\"rows\\":[{\\"row\\":[{\\"Text\\":\\"2\\"},{\\"Text\\":\\"7\\"},{\\"Text\\":\\"1\\"},{\\"Text\\":\\"3\\"}]},{\\"row\\":[{\\"Text\\":\\"7\\"},{\\"Text\\":\\"8\\"},{\\"Text\\":\\"0\\"},{\\"Text\\":\\"10\\"}]}]}"
                }
                """;

        var result = parser.parse(payload);

        assertThat(result.innings()).hasSize(2);
        assertThat(result.innings().get(0).inningNumber()).isEqualTo(1);
        assertThat(result.innings().get(0).awayRuns()).isEqualTo(0);
        assertThat(result.innings().get(0).homeRuns()).isEqualTo(3);
        assertThat(result.awayTotals().runs()).isEqualTo(2);
        assertThat(result.homeTotals().hits()).isEqualTo(8);
    }

    @Test
    void returnsEmptyForUnavailableScheduledScoreboard() {
        String payload = """
                {
                  "code": "200",
                  "msg": "입력 문자열의 형식이 잘못되었습니다."
                }
                """;

        var result = parser.parse(payload);

        assertThat(result.innings()).isEmpty();
        assertThat(result.awayTotals().isEmpty()).isTrue();
        assertThat(result.homeTotals().isEmpty()).isTrue();
        assertThat(result.rawHash()).isNotBlank();
    }
}
