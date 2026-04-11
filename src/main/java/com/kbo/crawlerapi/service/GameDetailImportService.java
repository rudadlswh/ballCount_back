package com.kbo.crawlerapi.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kbo.crawlerapi.api.ResourceNotFoundException;
import com.kbo.crawlerapi.crawler.KboGameDetailClient;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.LineScore;
import com.kbo.crawlerapi.parser.KboGameDetailParser;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedGameDetail;
import com.kbo.crawlerapi.parser.KboLineScoreParser;
import com.kbo.crawlerapi.parser.KboLineScoreParser.ParsedLineScoreResult;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;

@Service
public class GameDetailImportService {

    private final GameRepository gameRepository;
    private final GameSnapshotRepository gameSnapshotRepository;
    private final LineScoreRepository lineScoreRepository;
    private final KboGameDetailClient kboGameDetailClient;
    private final KboGameDetailParser kboGameDetailParser;
    private final KboLineScoreParser kboLineScoreParser;
    private final CrawlJobTrackingService crawlJobTrackingService;

    public GameDetailImportService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            KboGameDetailClient kboGameDetailClient,
            KboGameDetailParser kboGameDetailParser,
            KboLineScoreParser kboLineScoreParser,
            CrawlJobTrackingService crawlJobTrackingService
    ) {
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.lineScoreRepository = lineScoreRepository;
        this.kboGameDetailClient = kboGameDetailClient;
        this.kboGameDetailParser = kboGameDetailParser;
        this.kboLineScoreParser = kboLineScoreParser;
        this.crawlJobTrackingService = crawlJobTrackingService;
    }

    @Transactional
    public GameDetailImportResult importGameDetail(String publicGameId) {
        var crawlJob = crawlJobTrackingService.createRunningGameDetailImportJob(publicGameId);
        Game game;
        String detailResponseBody;
        String lineScoreResponseBody;
        ParsedGameDetail parsedDetail;
        ParsedLineScoreResult lineScoreResult;

        try {
            game = gameRepository.findByPublicGameId(publicGameId)
                    .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + publicGameId));
            if (game.getProviderGameId() == null || game.getProviderGameId().isBlank()) {
                throw new IllegalStateException("Provider game ID is not available yet for game " + publicGameId);
            }
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "lookup", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            detailResponseBody = kboGameDetailClient.fetchGameList(game.getGameDate());
            lineScoreResponseBody = kboGameDetailClient.fetchScoreBoard(game.getProviderGameId(), game.getGameDate().getYear());
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "request", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            parsedDetail = kboGameDetailParser.parseGameList(detailResponseBody).stream()
                    .filter(detail -> game.getProviderGameId().equals(detail.providerGameId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Official detail row not found for providerGameId " + game.getProviderGameId()
                    ));
            lineScoreResult = kboLineScoreParser.parse(lineScoreResponseBody);
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "parse", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            OffsetDateTime fetchedAt = OffsetDateTime.now();
            String combinedRawHash = hash(parsedDetail.rawHash() + ":" + lineScoreResult.rawHash());
            boolean snapshotCreated = persistSnapshotIfChanged(game, parsedDetail, lineScoreResult, combinedRawHash, fetchedAt);
            boolean lineScoresUpdated = syncLineScoresIfChanged(game, lineScoreResult.innings());
            game.syncDetail(
                    parsedDetail.status(),
                    parsedDetail.homeScore(),
                    parsedDetail.awayScore(),
                    parsedDetail.inningLabel(),
                    parsedDetail.isCancelled(),
                    parsedDetail.isPostponed(),
                    parsedDetail.cancelReason(),
                    parsedDetail.rawCancelText(),
                    parsedDetail.sourceUpdatedAt()
            );
            crawlJobTrackingService.markGameDetailSucceeded(crawlJob.getId(), snapshotCreated, lineScoreResult.innings().size());

            return new GameDetailImportResult(
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getGameDate(),
                    parsedDetail.status().getApiValue(),
                    snapshotCreated,
                    lineScoresUpdated,
                    lineScoreResult.innings().size(),
                    parsedDetail.awayScore(),
                    parsedDetail.homeScore(),
                    parsedDetail.inning(),
                    parsedDetail.inningHalf(),
                    parsedDetail.inningLabel(),
                    parsedDetail.sourceUpdatedAt(),
                    fetchedAt
            );
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "persist", exception.getMessage(), exception, 0);
            throw exception;
        }
    }

    private boolean persistSnapshotIfChanged(
            Game game,
            ParsedGameDetail parsedDetail,
            ParsedLineScoreResult lineScoreResult,
            String combinedRawHash,
            OffsetDateTime fetchedAt
    ) {
        var latestSnapshot = gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId());
        if (latestSnapshot.map(GameSnapshot::getRawHash).filter(combinedRawHash::equals).isPresent()) {
            return false;
        }

        gameSnapshotRepository.save(new GameSnapshot(
                UUID.randomUUID(),
                game,
                parsedDetail.inning(),
                parsedDetail.inningHalf(),
                parsedDetail.inningLabel(),
                parsedDetail.balls(),
                parsedDetail.strikes(),
                parsedDetail.outs(),
                parsedDetail.runnerOnFirst(),
                parsedDetail.runnerOnSecond(),
                parsedDetail.runnerOnThird(),
                parsedDetail.homeScore(),
                parsedDetail.awayScore(),
                lineScoreResult.homeTotals().hits(),
                lineScoreResult.awayTotals().hits(),
                lineScoreResult.homeTotals().errors(),
                lineScoreResult.awayTotals().errors(),
                lineScoreResult.homeTotals().balls(),
                lineScoreResult.awayTotals().balls(),
                combinedRawHash,
                parsedDetail.sourceUpdatedAt(),
                fetchedAt
        ));
        return true;
    }

    private boolean syncLineScoresIfChanged(Game game, List<KboLineScoreParser.ParsedLineScoreInning> innings) {
        List<LineScore> existingLineScores = lineScoreRepository.findByGame_IdOrderByInningNumberAsc(game.getId());
        if (lineScoresMatch(existingLineScores, innings)) {
            return false;
        }

        lineScoreRepository.deleteByGame_Id(game.getId());
        lineScoreRepository.flush();
        if (innings.isEmpty()) {
            return !existingLineScores.isEmpty();
        }

        lineScoreRepository.saveAll(innings.stream()
                .map(inning -> new LineScore(
                        UUID.randomUUID(),
                        game,
                        inning.inningNumber(),
                        inning.awayRuns(),
                        inning.homeRuns()
                ))
                .toList());
        return true;
    }

    private boolean lineScoresMatch(List<LineScore> existingLineScores, List<KboLineScoreParser.ParsedLineScoreInning> innings) {
        if (existingLineScores.size() != innings.size()) {
            return false;
        }
        for (int index = 0; index < existingLineScores.size(); index++) {
            LineScore existingLineScore = existingLineScores.get(index);
            KboLineScoreParser.ParsedLineScoreInning incomingLineScore = innings.get(index);
            if (existingLineScore.getInningNumber() != incomingLineScore.inningNumber()) {
                return false;
            }
            if (!java.util.Objects.equals(existingLineScore.getAwayRuns(), incomingLineScore.awayRuns())) {
                return false;
            }
            if (!java.util.Objects.equals(existingLineScore.getHomeRuns(), incomingLineScore.homeRuns())) {
                return false;
            }
        }
        return true;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
