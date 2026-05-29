package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameRepository.BoxscoreBackfillTarget;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GameDetailBoxscoreBackfillServiceTest {

    @Mock
    private GameRepository gameRepository;

    private StubGameDetailImportService gameDetailImportService;

    private GameDetailBoxscoreBackfillService service;

    @BeforeEach
    void setUp() {
        gameDetailImportService = new StubGameDetailImportService();
        service = new GameDetailBoxscoreBackfillService(gameRepository, gameDetailImportService);
    }

    @Test
    void importsSelectedProjectionTargetsByPublicGameId() {
        LocalDate gameDate = LocalDate.of(2026, 5, 28);
        BackfillTarget target = new BackfillTarget(
                UUID.randomUUID(),
                "20260528-LOT-LG",
                "20260528LGLT0",
                "final",
                OffsetDateTime.of(2026, 5, 28, 22, 10, 0, 0, ZoneOffset.ofHours(9)).toInstant()
        );
        when(gameRepository.findFinalBoxscoreBackfillTargetsByDate(eq(gameDate))).thenReturn(List.of(target));
        gameDetailImportService.result = result(target.getPublicGameId(), target.getProviderGameId());

        GameDetailBoxscoreBackfillService.GameDetailBackfillResult result = service.backfillFinalBoxscoreRecords(gameDate);

        assertThat(result.selectedGameCount()).isEqualTo(1);
        assertThat(result.succeededCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(result.runs().get(0).gameId()).isEqualTo(target.getId());
        assertThat(result.runs().get(0).publicGameId()).isEqualTo(target.getPublicGameId());
        assertThat(result.runs().get(0).providerGameId()).isEqualTo(target.getProviderGameId());
        assertThat(result.runs().get(0).succeeded()).isTrue();
        assertThat(gameDetailImportService.importedGameIds).containsExactly(target.getPublicGameId());
    }

    @Test
    void failureResultKeepsTargetIdentifiersAndRealImportError() {
        LocalDate gameDate = LocalDate.of(2026, 5, 28);
        BackfillTarget target = new BackfillTarget(
                UUID.randomUUID(),
                "20260528-SSG-SAM",
                "20260528SSSK0",
                "final",
                null
        );
        when(gameRepository.findFinalBoxscoreBackfillTargetsByDate(eq(gameDate))).thenReturn(List.of(target));
        gameDetailImportService.failure = new IllegalStateException("KBO boxscore endpoint returned error page");

        GameDetailBoxscoreBackfillService.GameDetailBackfillResult result = service.backfillFinalBoxscoreRecords(gameDate);

        assertThat(result.selectedGameCount()).isEqualTo(1);
        assertThat(result.succeededCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.runs().get(0).publicGameId()).isEqualTo(target.getPublicGameId());
        assertThat(result.runs().get(0).providerGameId()).isEqualTo(target.getProviderGameId());
        assertThat(result.runs().get(0).errorMessage()).isEqualTo("KBO boxscore endpoint returned error page");
    }

    @Test
    void skipsImportWhenNoMissingFinalTargetsAreSelected() {
        LocalDate gameDate = LocalDate.of(2026, 5, 28);
        when(gameRepository.findFinalBoxscoreBackfillTargetsByDate(eq(gameDate))).thenReturn(List.of());

        GameDetailBoxscoreBackfillService.GameDetailBackfillResult result = service.backfillFinalBoxscoreRecords(gameDate);

        assertThat(result.selectedGameCount()).isZero();
        assertThat(result.succeededCount()).isZero();
        assertThat(result.failedCount()).isZero();
        assertThat(gameDetailImportService.importedGameIds).isEmpty();
    }

    private GameDetailImportResult result(String publicGameId, String providerGameId) {
        return new GameDetailImportResult(
                publicGameId,
                providerGameId,
                LocalDate.of(2026, 5, 28),
                "final",
                true,
                true,
                9,
                2,
                7,
                9,
                "top",
                "Top 9",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private record BackfillTarget(
            UUID id,
            String publicGameId,
            String providerGameId,
            String status,
            Instant finalConfirmedAt
    ) implements BoxscoreBackfillTarget {
        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public String getPublicGameId() {
            return publicGameId;
        }

        @Override
        public String getProviderGameId() {
            return providerGameId;
        }

        @Override
        public String getStatus() {
            return status;
        }

        @Override
        public Instant getFinalConfirmedAt() {
            return finalConfirmedAt;
        }
    }

    private static final class StubGameDetailImportService extends GameDetailImportService {

        private final java.util.List<String> importedGameIds = new java.util.ArrayList<>();
        private GameDetailImportResult result;
        private RuntimeException failure;

        private StubGameDetailImportService() {
            super(null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public GameDetailImportResult importGameDetail(String publicGameId) {
            importedGameIds.add(publicGameId);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
