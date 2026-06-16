package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedLineupData;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedLineupPlayer;
import com.kbo.crawlerapi.parser.KboLiveTextParser.ParsedLiveText;
import com.kbo.crawlerapi.parser.KboLiveTextParser.ParsedLiveTextBatterRecord;
import com.kbo.crawlerapi.repository.GameEventWriteRepository;
import com.kbo.crawlerapi.repository.GameEventWriteRepository.GameEventWriteRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        return saveLiveText(game, parsedLiveText, sourceUpdatedAt, null);
    }

    @Transactional
    public GameLiveTextRecordSaveResult saveLiveText(
            Game game,
            ParsedLiveText parsedLiveText,
            OffsetDateTime sourceUpdatedAt,
            ParsedLineupData lineupData
    ) {
        if (game == null || parsedLiveText == null) {
            return new GameLiveTextRecordSaveResult(0, 0, 0, 0, "empty");
        }

        int batterCount = 0;
        int pitcherCount = 0;
        if (parsedLiveText.hasRecords() && game.getStatus() != GameStatus.FINAL) {
            var result = gameBoxscoreRecordService.saveBoxscoreRecords(
                    game,
                    enrichBatterRecords(parsedLiveText, lineupData).toParsedBoxscore()
            );
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

    private ParsedLiveText enrichBatterRecords(ParsedLiveText parsedLiveText, ParsedLineupData lineupData) {
        List<ParsedLineupPlayer> awayLineup = lineupData == null ? List.of() : lineupData.away();
        List<ParsedLineupPlayer> homeLineup = lineupData == null ? List.of() : lineupData.home();
        return new ParsedLiveText(
                enrichBatterRecords(parsedLiveText.awayBatters(), awayLineup),
                enrichBatterRecords(parsedLiveText.homeBatters(), homeLineup),
                parsedLiveText.awayPitchers(),
                parsedLiveText.homePitchers(),
                parsedLiveText.events(),
                parsedLiveText.eventCandidateCount(),
                parsedLiveText.skippedEventCount()
        );
    }

    private List<ParsedLiveTextBatterRecord> enrichBatterRecords(
            List<ParsedLiveTextBatterRecord> records,
            List<ParsedLineupPlayer> lineup
    ) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        LineupPositionResolver resolver = new LineupPositionResolver(lineup);
        List<ParsedLiveTextBatterRecord> enriched = new ArrayList<>(records.size());
        for (ParsedLiveTextBatterRecord record : records) {
            Integer battingOrder = record.battingOrder() == null ? record.sourceOrder() + 1 : record.battingOrder();
            String position = hasText(record.position())
                    ? record.position().trim()
                    : resolver.resolve(record, battingOrder);
            enriched.add(new ParsedLiveTextBatterRecord(
                    record.teamSide(),
                    record.sourceGroupIndex(),
                    battingOrder,
                    position,
                    record.playerName(),
                    record.atBats(),
                    record.runs(),
                    record.hits(),
                    record.rbi(),
                    record.homeRuns(),
                    record.walks(),
                    record.strikeouts(),
                    record.stolenBases(),
                    record.sacrificeHits(),
                    record.groundedIntoDoublePlay(),
                    record.errors(),
                    record.sourceOrder()
            ));
        }
        return enriched;
    }

    private static final class LineupPositionResolver {

        private final Map<Integer, String> positionByBattingOrder = new LinkedHashMap<>();
        private final Map<String, String> positionByPlayerName = new LinkedHashMap<>();
        private final Map<Integer, String> positionByLineupIndex = new LinkedHashMap<>();

        private LineupPositionResolver(List<ParsedLineupPlayer> lineup) {
            if (lineup == null) {
                return;
            }
            for (int index = 0; index < lineup.size(); index++) {
                ParsedLineupPlayer player = lineup.get(index);
                if (player == null || !hasText(player.position())) {
                    continue;
                }
                String position = player.position().trim();
                Integer battingOrder = parseInteger(player.battingOrder());
                if (battingOrder != null) {
                    positionByBattingOrder.putIfAbsent(battingOrder, position);
                }
                String normalizedName = normalizePlayerName(player.name());
                if (normalizedName != null) {
                    positionByPlayerName.putIfAbsent(normalizedName, position);
                }
                positionByLineupIndex.putIfAbsent(index, position);
            }
        }

        private String resolve(ParsedLiveTextBatterRecord record, Integer battingOrder) {
            if (battingOrder != null) {
                String byOrder = positionByBattingOrder.get(battingOrder);
                if (hasText(byOrder)) {
                    return byOrder;
                }
            }
            String normalizedName = normalizePlayerName(record.playerName());
            if (normalizedName != null) {
                String byName = positionByPlayerName.get(normalizedName);
                if (hasText(byName)) {
                    return byName;
                }
            }
            return positionByLineupIndex.get(record.sourceOrder());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Integer parseInteger(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String normalizePlayerName(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", "")
                .replace("·", "")
                .replace(".", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
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
