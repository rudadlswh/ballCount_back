package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedBatterRecord;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedBoxscore;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedPitcherRecord;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordWriteRepository;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordWriteRepository.BatterRecordWriteRow;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordWriteRepository.PitcherRecordWriteRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameBoxscoreRecordService {

    private static final Logger log = LoggerFactory.getLogger(GameBoxscoreRecordService.class);

    private final GameBoxscoreRecordWriteRepository writeRepository;

    public GameBoxscoreRecordService(GameBoxscoreRecordWriteRepository writeRepository) {
        this.writeRepository = writeRepository;
    }

    @Transactional
    public GameBoxscoreRecordSaveResult saveBoxscoreRecords(Game game, ParsedBoxscore parsedBoxscore) {
        if (game == null || parsedBoxscore == null) {
            return new GameBoxscoreRecordSaveResult(0, 0, 0, 0, false);
        }

        List<BatterRecordWriteRow> batterRows = new ArrayList<>();
        batterRows.addAll(toBatterRows(game.getId(), game.getAwayTeam(), parsedBoxscore.awayBatters()));
        batterRows.addAll(toBatterRows(game.getId(), game.getHomeTeam(), parsedBoxscore.homeBatters()));

        List<PitcherRecordWriteRow> pitcherRows = new ArrayList<>();
        pitcherRows.addAll(toPitcherRows(game.getId(), game.getAwayTeam(), parsedBoxscore.awayPitchers()));
        pitcherRows.addAll(toPitcherRows(game.getId(), game.getHomeTeam(), parsedBoxscore.homePitchers()));

        int batterWriteCount = writeRepository.upsertBatterRecords(batterRows);
        int pitcherWriteCount = writeRepository.upsertPitcherRecords(pitcherRows);
        boolean saved = !batterRows.isEmpty() || !pitcherRows.isEmpty();
        log.info(
                "[GameBoxscoreRecords] gameId={} awayBatters={} homeBatters={} awayPitchers={} homePitchers={} batters={} pitchers={} saved={}",
                game.getPublicGameId(),
                parsedBoxscore.awayBatters().size(),
                parsedBoxscore.homeBatters().size(),
                parsedBoxscore.awayPitchers().size(),
                parsedBoxscore.homePitchers().size(),
                batterRows.size(),
                pitcherRows.size(),
                saved
        );
        return new GameBoxscoreRecordSaveResult(
                batterRows.size(),
                pitcherRows.size(),
                batterWriteCount,
                pitcherWriteCount,
                saved
        );
    }

    private List<BatterRecordWriteRow> toBatterRows(UUID gameId, Team team, List<ParsedBatterRecord> records) {
        if (team == null || records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(record -> new BatterRecordWriteRow(
                        gameId,
                        team.getId(),
                        record.sourceOrder(),
                        record.battingOrder(),
                        record.position(),
                        record.playerName(),
                        record.atBats(),
                        record.runs(),
                        record.hits(),
                        record.rbi(),
                        record.homeRuns(),
                        record.walks(),
                        record.strikeouts(),
                        record.stolenBases(),
                        record.groundedIntoDoublePlay(),
                        record.errors(),
                        decimalString(record.battingAverage())
                ))
                .toList();
    }

    private List<PitcherRecordWriteRow> toPitcherRows(UUID gameId, Team team, List<ParsedPitcherRecord> records) {
        if (team == null || records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(record -> new PitcherRecordWriteRow(
                        gameId,
                        team.getId(),
                        record.sourceOrder(),
                        record.pitchingOrder(),
                        record.playerName(),
                        record.appearance(),
                        record.decision(),
                        record.wins(),
                        record.losses(),
                        record.saves(),
                        record.inningsPitched(),
                        record.battersFaced(),
                        record.pitchCount(),
                        record.atBats(),
                        record.hits(),
                        record.homeRuns(),
                        record.walksOrHitByPitch(),
                        record.strikeouts(),
                        record.runs(),
                        record.earnedRuns(),
                        decimalString(record.era())
                ))
                .toList();
    }

    private String decimalString(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    public record GameBoxscoreRecordSaveResult(
            int batterRecordCount,
            int pitcherRecordCount,
            int batterWriteCount,
            int pitcherWriteCount,
            boolean saved
    ) {
    }
}
