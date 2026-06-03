package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.parser.KboLiveTextParser.ParsedLiveText;
import com.kbo.crawlerapi.repository.GameEventWriteRepository;
import com.kbo.crawlerapi.repository.GameEventWriteRepository.GameEventWriteRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameLiveTextRecordService {

    private static final Logger log = LoggerFactory.getLogger(GameLiveTextRecordService.class);

    private final GameBoxscoreRecordService gameBoxscoreRecordService;
    private final GameEventWriteRepository gameEventWriteRepository;

    public GameLiveTextRecordService(
            GameBoxscoreRecordService gameBoxscoreRecordService,
            GameEventWriteRepository gameEventWriteRepository
    ) {
        this.gameBoxscoreRecordService = gameBoxscoreRecordService;
        this.gameEventWriteRepository = gameEventWriteRepository;
    }

    @Transactional
    public GameLiveTextRecordSaveResult saveLiveText(Game game, ParsedLiveText parsedLiveText, OffsetDateTime sourceUpdatedAt) {
        if (game == null || parsedLiveText == null) {
            return new GameLiveTextRecordSaveResult(0, 0, 0, 0, "empty");
        }

        int batterCount = 0;
        int pitcherCount = 0;
        if (parsedLiveText.hasRecords() && game.getStatus() != GameStatus.FINAL) {
            var result = gameBoxscoreRecordService.saveBoxscoreRecords(game, parsedLiveText.toParsedBoxscore());
            batterCount = result.batterRecordCount();
            pitcherCount = result.pitcherRecordCount();
        } else if (parsedLiveText.hasRecords()) {
            log.info("[KboLiveText] record persistence skipped gameId={} reason=finalBoxscorePriority", game.getPublicGameId());
        }

        List<GameEventWriteRow> eventRows = parsedLiveText.events().stream()
                .map(event -> new GameEventWriteRow(
                        game.getId(),
                        providerEventId(event.sequenceNumber(), event.eventText()),
                        event.sequenceNumber(),
                        event.inning(),
                        event.inningHalf(),
                        event.eventType(),
                        event.eventText(),
                        sourceUpdatedAt
                ))
                .toList();
        int parsedEventCount = eventRows.size();
        int eventWriteCount = gameEventWriteRepository.upsertEvents(eventRows);
        int persistenceSkippedCount = Math.max(0, parsedEventCount - eventWriteCount);
        if (parsedEventCount > 0 && eventWriteCount == 0) {
            log.warn(
                    "[KboLiveText] event persistence skipped gameId={} providerGameId={} parsedEventCount={} skippedReason=noRowsSaved",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    parsedEventCount
            );
        } else if (persistenceSkippedCount > 0) {
            log.warn(
                    "[KboLiveText] event persistence partially skipped gameId={} providerGameId={} parsedEventCount={} savedEventCount={} skippedEventCount={} skippedReason=batchWriteCountMismatch",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    parsedEventCount,
                    eventWriteCount,
                    persistenceSkippedCount
            );
        }
        log.info(
                "[KboLiveText] persisted gameId={} providerGameId={} batters={} pitchers={} eventCandidateCount={} parsedEventCount={} savedEventCount={} skippedEventCount={}",
                game.getPublicGameId(),
                game.getProviderGameId(),
                batterCount,
                pitcherCount,
                parsedLiveText.eventCandidateCount(),
                parsedEventCount,
                eventWriteCount,
                parsedLiveText.skippedEventCount() + persistenceSkippedCount
        );
        return new GameLiveTextRecordSaveResult(batterCount, pitcherCount, parsedEventCount, eventWriteCount, null);
    }

    private String providerEventId(int sequenceNumber, String eventText) {
        return "livetext:%d:%s".formatted(sequenceNumber, hash(eventText));
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception exception) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public record GameLiveTextRecordSaveResult(
            int batterRecordCount,
            int pitcherRecordCount,
            int eventCount,
            int eventWriteCount,
            String skippedReason
    ) {
    }
}
