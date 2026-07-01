package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedLineupData;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedLineupPlayer;
import com.kbo.crawlerapi.parser.KboLiveTextParser.ParsedLiveText;
import com.kbo.crawlerapi.parser.KboLiveTextParser.ParsedLiveTextBatterRecord;
import com.kbo.crawlerapi.repository.GameEventWriteRepository;
import com.kbo.crawlerapi.repository.GameEventWriteRepository.GameEventWriteRow;
import com.kbo.crawlerapi.support.HashSupport;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
                    enrichBatterRecords(game, parsedLiveText, lineupData).toParsedBoxscore()
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

    private ParsedLiveText enrichBatterRecords(Game game, ParsedLiveText parsedLiveText, ParsedLineupData lineupData) {
        List<ParsedLineupPlayer> awayLineup = lineupForTeam(lineupData, game.getAwayTeam(), true);
        List<ParsedLineupPlayer> homeLineup = lineupForTeam(lineupData, game.getHomeTeam(), false);
        return new ParsedLiveText(
                enrichBatterRecords(game, game.getAwayTeam(), parsedLiveText.awayBatters(), awayLineup),
                enrichBatterRecords(game, game.getHomeTeam(), parsedLiveText.homeBatters(), homeLineup),
                parsedLiveText.awayPitchers(),
                parsedLiveText.homePitchers(),
                parsedLiveText.events(),
                parsedLiveText.eventCandidateCount(),
                parsedLiveText.skippedEventCount()
        );
    }

    private List<ParsedLineupPlayer> lineupForTeam(ParsedLineupData lineupData, Team team, boolean awayFallback) {
        if (lineupData == null || !lineupData.hasLineups()) {
            return List.of();
        }
        String normalizedTeam = normalizeTeamIdentity(team);
        List<ParsedLineupPlayer> all = new ArrayList<>();
        all.addAll(lineupData.away());
        all.addAll(lineupData.home());
        if (normalizedTeam != null) {
            List<ParsedLineupPlayer> matched = all.stream()
                    .filter(player -> normalizedTeam.equals(normalizeTeamCode(player.teamCode())))
                    .toList();
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        return awayFallback ? lineupData.away() : lineupData.home();
    }

    private List<ParsedLiveTextBatterRecord> enrichBatterRecords(
            Game game,
            Team team,
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
            String beforePosition = record.position();
            String position = hasText(record.position())
                    ? record.position().trim()
                    : resolver.resolve(record, battingOrder);
            log.info(
                    "[KboLiveTextPositionMerge] gameId={} publicGameId={} teamId={} teamCode={} normalizedTeam={} playerName={} sourceOrder={} battingOrder={} beforePosition={} mergedPosition={} lineupCandidateCount={}",
                    game.getId(),
                    game.getPublicGameId(),
                    team == null ? null : team.getId(),
                    team == null ? null : team.getTeamCode(),
                    normalizeTeamIdentity(team),
                    record.playerName(),
                    record.sourceOrder(),
                    battingOrder,
                    beforePosition,
                    position,
                    lineup.size()
            );
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

    private static String normalizeTeamIdentity(Team team) {
        if (team == null) {
            return null;
        }
        String byCode = normalizeTeamCode(team.getTeamCode());
        if (byCode != null) {
            return byCode;
        }
        String byName = normalizeTeamCode(team.getShortName());
        if (byName != null) {
            return byName;
        }
        return normalizeTeamCode(team.getName());
    }

    private static String normalizeTeamCode(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "lt", "lot", "lotte", "롯데", "롯데자이언츠" -> "lotte";
            case "sk", "ssg", "ssg랜더스" -> "ssg";
            case "ob", "doosan", "두산", "두산베어스" -> "doosan";
            case "hh", "hanwha", "한화", "한화이글스" -> "hanwha";
            case "ht", "kia", "기아", "kia타이거즈", "기아타이거즈" -> "kia";
            case "wo", "kiwoom", "키움", "키움히어로즈" -> "kiwoom";
            case "kt", "kt위즈" -> "kt";
            case "lg", "lg트윈스" -> "lg";
            case "nc", "nc다이노스" -> "nc";
            case "ss", "samsung", "삼성", "삼성라이온즈" -> "samsung";
            default -> normalized.isBlank() ? null : normalized;
        };
    }

    private String providerEventId(int sequenceNumber, String eventText) {
        return "livetext:%d:%s".formatted(sequenceNumber, hash(eventText));
    }

    private String hash(String value) {
        try {
            return HashSupport.sha256HexPrefix(value, 16);
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
