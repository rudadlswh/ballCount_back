package com.kbo.crawlerapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.service.GameDetailBoxscoreBackfillService;
import com.kbo.crawlerapi.service.GameDetailBoxscoreBackfillService.GameDetailBackfillResult;
import com.kbo.crawlerapi.service.GameDetailBoxscoreBackfillService.GameDetailBackfillRunResult;
import com.kbo.crawlerapi.service.GameDetailImportService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InternalGameDetailImportControllerTest {

    @Test
    void backfillEndpointReturnsProjectionIdentifiersFromService() {
        LocalDate gameDate = LocalDate.of(2026, 5, 28);
        UUID gameId = UUID.randomUUID();
        GameDetailBackfillResult serviceResult = new GameDetailBackfillResult(
                gameDate,
                1,
                0,
                1,
                List.of(new GameDetailBackfillRunResult(
                        gameId,
                        "20260528-LOT-LG",
                        "20260528LGLT0",
                        false,
                        "KBO boxscore endpoint returned error page",
                        null
                ))
        );
        StubGameDetailBoxscoreBackfillService gameDetailBoxscoreBackfillService = new StubGameDetailBoxscoreBackfillService(serviceResult);
        InternalGameDetailImportController controller = new InternalGameDetailImportController(
                new StubGameDetailImportService(),
                gameDetailBoxscoreBackfillService
        );

        GameDetailBackfillResult response = controller.backfillFinalBoxscoreRecords(gameDate);

        assertThat(response.selectedGameCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.runs().get(0).gameId()).isEqualTo(gameId);
        assertThat(response.runs().get(0).publicGameId()).isEqualTo("20260528-LOT-LG");
        assertThat(response.runs().get(0).providerGameId()).isEqualTo("20260528LGLT0");
    }

    private static final class StubGameDetailImportService extends GameDetailImportService {

        private StubGameDetailImportService() {
            super(null, null, null, null, null, null, null, null, null, null);
        }
    }

    private static final class StubGameDetailBoxscoreBackfillService extends GameDetailBoxscoreBackfillService {

        private final GameDetailBackfillResult result;

        private StubGameDetailBoxscoreBackfillService(GameDetailBackfillResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public GameDetailBackfillResult backfillFinalBoxscoreRecords(LocalDate gameDate) {
            return result;
        }
    }
}
