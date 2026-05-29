package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameRepository.BoxscoreBackfillTarget;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GameDetailBoxscoreBackfillService {

    private static final Logger log = LoggerFactory.getLogger(GameDetailBoxscoreBackfillService.class);

    private final GameRepository gameRepository;
    private final GameDetailImportService gameDetailImportService;

    public GameDetailBoxscoreBackfillService(
            GameRepository gameRepository,
            GameDetailImportService gameDetailImportService
    ) {
        this.gameRepository = gameRepository;
        this.gameDetailImportService = gameDetailImportService;
    }

    public GameDetailBackfillResult backfillFinalBoxscoreRecords(LocalDate gameDate) {
        LocalDate targetDate = Objects.requireNonNull(gameDate, "gameDate must not be null");
        List<BoxscoreBackfillTarget> targets = gameRepository.findFinalBoxscoreBackfillTargetsByDate(targetDate);
        log.info("[GameBoxscoreImport] backfill selected targetCount={} date={}", targets.size(), targetDate);

        List<GameDetailBackfillRunResult> runs = new ArrayList<>(targets.size());
        int succeeded = 0;
        int failed = 0;
        for (BoxscoreBackfillTarget target : targets) {
            log.info(
                    "[GameBoxscoreImport] backfill target publicGameId={} providerGameId={} status={} finalConfirmedAt={}",
                    target.getPublicGameId(),
                    target.getProviderGameId(),
                    target.getStatus(),
                    target.getFinalConfirmedAt()
            );
            try {
                log.info(
                        "[GameBoxscoreImport] backfill import start publicGameId={} providerGameId={}",
                        target.getPublicGameId(),
                        target.getProviderGameId()
                );
                GameDetailImportResult result = gameDetailImportService.importGameDetail(target.getPublicGameId());
                runs.add(new GameDetailBackfillRunResult(
                        target.getId(),
                        target.getPublicGameId(),
                        target.getProviderGameId(),
                        true,
                        null,
                        result.status()
                ));
                succeeded++;
            } catch (RuntimeException exception) {
                log.warn(
                        "[GameBoxscoreImport] backfill import failed publicGameId={} providerGameId={} stage=import message={}",
                        target.getPublicGameId(),
                        target.getProviderGameId(),
                        exception.getMessage()
                );
                runs.add(new GameDetailBackfillRunResult(
                        target.getId(),
                        target.getPublicGameId(),
                        target.getProviderGameId(),
                        false,
                        exception.getMessage(),
                        null
                ));
                failed++;
            }
        }

        log.info(
                "[GameBoxscoreImport] backfill complete date={} selected={} succeeded={} failed={}",
                targetDate,
                targets.size(),
                succeeded,
                failed
        );
        return new GameDetailBackfillResult(targetDate, targets.size(), succeeded, failed, runs);
    }

    public record GameDetailBackfillResult(
            LocalDate gameDate,
            int selectedGameCount,
            int succeededCount,
            int failedCount,
            List<GameDetailBackfillRunResult> runs
    ) {
    }

    public record GameDetailBackfillRunResult(
            UUID gameId,
            String publicGameId,
            String providerGameId,
            boolean succeeded,
            String errorMessage,
            String status
    ) {
    }
}
