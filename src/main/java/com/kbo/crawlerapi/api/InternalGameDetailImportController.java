package com.kbo.crawlerapi.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kbo.crawlerapi.api.InvalidParameterException;
import com.kbo.crawlerapi.service.GameDetailImportResult;
import com.kbo.crawlerapi.service.GameDetailImportService;
import io.swagger.v3.oas.annotations.Hidden;

@RestController
@RequestMapping("/internal")
@Hidden
public class InternalGameDetailImportController {

    private final GameDetailImportService gameDetailImportService;

    public InternalGameDetailImportController(GameDetailImportService gameDetailImportService) {
        this.gameDetailImportService = gameDetailImportService;
    }

    @PostMapping("/games/{gameId}/detail/import")
    public GameDetailImportResponse importGameDetail(@PathVariable String gameId) {
        GameDetailImportResult result = gameDetailImportService.importGameDetail(gameId);
        return new GameDetailImportResponse(
                result.gameId(),
                result.providerGameId(),
                result.gameDate(),
                result.status(),
                result.snapshotCreated(),
                result.lineScoresUpdated(),
                result.lineScoreCount(),
                result.awayScore(),
                result.homeScore(),
                result.inning(),
                result.inningHalf(),
                result.inningLabel(),
                result.sourceUpdatedAt(),
                result.fetchedAt()
        );
    }

    @PostMapping("/games/{gameId}/detail/refresh")
    public GameDetailRefreshResponse refreshGameDetail(
            @PathVariable String gameId,
            @RequestParam(defaultValue = "1") int repeat
    ) {
        if (repeat < 1 || repeat > 10) {
            throw new InvalidParameterException("repeat must be in the range 1-10");
        }
        List<GameDetailImportResponse> runs = java.util.stream.IntStream.range(0, repeat)
                .mapToObj(index -> importGameDetail(gameId))
                .toList();
        return new GameDetailRefreshResponse(gameId, repeat, runs);
    }

    public record GameDetailImportResponse(
            String gameId,
            String providerGameId,
            LocalDate gameDate,
            String status,
            boolean snapshotCreated,
            boolean lineScoresUpdated,
            int lineScoreCount,
            Integer awayScore,
            Integer homeScore,
            Integer inning,
            String inningHalf,
            String inningLabel,
            OffsetDateTime sourceUpdatedAt,
            OffsetDateTime fetchedAt
    ) {
    }

    public record GameDetailRefreshResponse(
            String gameId,
            int repeat,
            List<GameDetailImportResponse> runs
    ) {
    }
}
