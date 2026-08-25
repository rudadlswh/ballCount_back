package com.kbo.crawlerapi.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

class KboScheduleParserSkipPolicyTest {

    private final KboScheduleParser parser = new KboScheduleParser(new ObjectMapper());

    @Test
    void keepsRowsWithoutProviderGameIdAsImportableSchedules() {
        String payload = """
                {
                  "rows": [
                    {
                      "row": [
                        { "Text": "04.05(\\uD1A0)" },
                        { "Text": "<b>14:00</b>" },
                        { "Text": "<span>KIA</span><em><span>vs</span></em><span>LG</span>" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "" },
                        { "Text": "\\uC7A0\\uC2E4" },
                        { "Text": "\\uC6B0\\uCC9C\\uCDE8\\uC18C" }
                      ]
                    }
                  ]
                }
                """;

        var result = parser.parseMonthlyScheduleResult(payload, YearMonth.of(2025, 4));

        assertThat(result.games()).hasSize(1);
        assertThat(result.games().get(0).providerGameId()).isNull();
        assertThat(result.skippedRows()).isEmpty();
        assertThat(result.skippedMissingProviderGameIdCount()).isZero();
    }
}
