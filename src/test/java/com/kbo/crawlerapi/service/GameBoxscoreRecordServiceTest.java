package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboBoxscoreParser;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedBatterRecord;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedBoxscore;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedPitcherRecord;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordWriteRepository;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordWriteRepository.BatterRecordWriteRow;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordWriteRepository.PitcherRecordWriteRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameBoxscoreRecordServiceTest {

    private final KboBoxscoreParser parser = new KboBoxscoreParser(new ObjectMapper());

    private InMemoryGameBoxscoreRecordWriteRepository writeRepository;
    private GameBoxscoreRecordService service;
    private Team awayTeam;
    private Team homeTeam;
    private Game game;

    @BeforeEach
    void setUp() {
        writeRepository = new InMemoryGameBoxscoreRecordWriteRepository();
        service = new GameBoxscoreRecordService(writeRepository);
        awayTeam = new Team(UUID.randomUUID(), "ssg", "SSG 랜더스", "SSG", "SSG Landers", null);
        homeTeam = new Team(UUID.randomUUID(), "doosan", "두산 베어스", "두산", "Doosan Bears", null);
        game = new Game(
                UUID.randomUUID(),
                "20260510SKOB0",
                "kbo",
                "20260510SKOB0",
                LocalDate.of(2026, 5, 10),
                OffsetDateTime.of(2026, 5, 10, 14, 0, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.FINAL,
                homeTeam,
                awayTeam,
                3,
                1,
                "Top 9",
                false,
                false,
                null,
                null,
                null
        );
    }

    @Test
    void savesAwayAndHomeBatterRecordsWithCorrectTeamIdsAndSourceOrder() throws IOException {
        ParsedBoxscore parsed = parser.parse(fixture());

        var result = service.saveBoxscoreRecords(game, parsed);

        assertThat(result.batterRecordCount()).isEqualTo(29);
        assertThat(writeRepository.batterRows).hasSize(29);

        BatterRecordWriteRow awayFirst = writeRepository.batterRows.get(new RowKey(game.getId(), awayTeam.getId(), 0));
        assertThat(awayFirst.playerName()).isEqualTo("안상현");
        assertThat(awayFirst.teamId()).isEqualTo(awayTeam.getId());
        assertThat(awayFirst.sourceOrder()).isZero();
        assertThat(awayFirst.battingOrder()).isEqualTo(1);
        assertThat(awayFirst.position()).isEqualTo("유");
        assertThat(awayFirst.atBats()).isEqualTo(3);
        assertThat(awayFirst.runs()).isZero();
        assertThat(awayFirst.hits()).isEqualTo(1);
        assertThat(awayFirst.rbi()).isZero();
        assertThat(awayFirst.battingAverage()).isEqualTo("0.300");
        assertThat(awayFirst.homeRuns()).isZero();
        assertThat(awayFirst.walks()).isZero();
        assertThat(awayFirst.strikeouts()).isZero();
        assertThat(awayFirst.stolenBases()).isNull();

        BatterRecordWriteRow homeFirst = writeRepository.batterRows.get(new RowKey(game.getId(), homeTeam.getId(), 0));
        assertThat(homeFirst.playerName()).isEqualTo("박찬호");
        assertThat(homeFirst.teamId()).isEqualTo(homeTeam.getId());
        assertThat(homeFirst.sourceOrder()).isZero();
    }

    @Test
    void savesAwayAndHomePitcherRecordsWithCorrectTeamIdsAndSourceFields() throws IOException {
        ParsedBoxscore parsed = parser.parse(fixture());

        var result = service.saveBoxscoreRecords(game, parsed);

        assertThat(result.pitcherRecordCount()).isEqualTo(10);
        assertThat(writeRepository.pitcherRows).hasSize(10);

        PitcherRecordWriteRow awayStarter = writeRepository.pitcherRows.get(new RowKey(game.getId(), awayTeam.getId(), 0));
        assertThat(awayStarter.playerName()).isEqualTo("최민준");
        assertThat(awayStarter.teamId()).isEqualTo(awayTeam.getId());
        assertThat(awayStarter.sourceOrder()).isZero();
        assertThat(awayStarter.pitchingOrder()).isEqualTo(1);
        assertThat(awayStarter.appearance()).isEqualTo("선발");
        assertThat(awayStarter.decisionResult()).isEqualTo("패");
        assertThat(awayStarter.inningsPitched()).isEqualTo("2");
        assertThat(awayStarter.battersFaced()).isEqualTo(12);
        assertThat(awayStarter.pitchCount()).isEqualTo(46);
        assertThat(awayStarter.atBats()).isEqualTo(8);
        assertThat(awayStarter.hits()).isEqualTo(3);
        assertThat(awayStarter.homeRuns()).isEqualTo(1);
        assertThat(awayStarter.walksOrHitByPitch()).isEqualTo(3);
        assertThat(awayStarter.strikeouts()).isZero();
        assertThat(awayStarter.runs()).isEqualTo(3);
        assertThat(awayStarter.earnedRuns()).isEqualTo(2);
        assertThat(awayStarter.era()).isEqualTo("3.23");

        PitcherRecordWriteRow homeStarter = writeRepository.pitcherRows.get(new RowKey(game.getId(), homeTeam.getId(), 0));
        assertThat(homeStarter.playerName()).isEqualTo("잭로그");
        assertThat(homeStarter.teamId()).isEqualTo(homeTeam.getId());
        assertThat(homeStarter.decisionResult()).isEqualTo("승");
        assertThat(homeStarter.inningsPitched()).isEqualTo("6 1/3");
        assertThat(homeStarter.era()).isEqualTo("3.19");
    }

    @Test
    void reimportingSameBoxscoreDoesNotCreateDuplicateRows() throws IOException {
        ParsedBoxscore parsed = parser.parse(fixture());

        service.saveBoxscoreRecords(game, parsed);
        service.saveBoxscoreRecords(game, parsed);

        assertThat(writeRepository.batterRows).hasSize(29);
        assertThat(writeRepository.pitcherRows).hasSize(10);
    }

    @Test
    void reimportDeletesStaleRowsBeforeSavingLatestBoxscore() throws IOException {
        ParsedBoxscore parsed = parser.parse(fixture());
        service.saveBoxscoreRecords(game, parsed);

        ParsedBoxscore changed = new ParsedBoxscore(
                parsed.awayBatters().subList(0, 1),
                List.of(),
                parsed.awayPitchers().subList(0, 1),
                List.of()
        );
        var result = service.saveBoxscoreRecords(game, changed);

        assertThat(result.deletedBatterCount()).isEqualTo(29);
        assertThat(result.deletedPitcherCount()).isEqualTo(10);
        assertThat(writeRepository.batterRows).hasSize(1);
        assertThat(writeRepository.pitcherRows).hasSize(1);
        assertThat(writeRepository.batterRows.values()).extracting(BatterRecordWriteRow::playerName)
                .containsExactly(parsed.awayBatters().get(0).playerName());
        assertThat(writeRepository.pitcherRows.values()).extracting(PitcherRecordWriteRow::playerName)
                .containsExactly(parsed.awayPitchers().get(0).playerName());
    }

    @Test
    void changedParsedValuesUpdateExistingRowsByGameTeamAndSourceOrder() throws IOException {
        ParsedBoxscore parsed = parser.parse(fixture());
        service.saveBoxscoreRecords(game, parsed);

        ParsedBoxscore changed = new ParsedBoxscore(
                replaceFirstBatter(parsed.awayBatters(), 9, 7, 8, 6, 2, 1, 3),
                parsed.homeBatters(),
                replaceFirstPitcher(parsed.awayPitchers(), 99, "9.99"),
                parsed.homePitchers()
        );
        service.saveBoxscoreRecords(game, changed);

        assertThat(writeRepository.batterRows).hasSize(29);
        assertThat(writeRepository.pitcherRows).hasSize(10);
        BatterRecordWriteRow awayFirst = writeRepository.batterRows.get(new RowKey(game.getId(), awayTeam.getId(), 0));
        assertThat(awayFirst.atBats()).isEqualTo(9);
        assertThat(awayFirst.runs()).isEqualTo(7);
        assertThat(awayFirst.hits()).isEqualTo(8);
        assertThat(awayFirst.rbi()).isEqualTo(6);
        assertThat(awayFirst.homeRuns()).isEqualTo(2);
        assertThat(awayFirst.walks()).isEqualTo(1);
        assertThat(awayFirst.strikeouts()).isEqualTo(3);
        PitcherRecordWriteRow awayStarter = writeRepository.pitcherRows.get(new RowKey(game.getId(), awayTeam.getId(), 0));
        assertThat(awayStarter.pitchCount()).isEqualTo(99);
        assertThat(awayStarter.era()).isEqualTo("9.99");
    }

    private List<ParsedBatterRecord> replaceFirstBatter(
            List<ParsedBatterRecord> records,
            Integer atBats,
            Integer runs,
            Integer hits,
            Integer rbi,
            Integer homeRuns,
            Integer walks,
            Integer strikeouts
    ) {
        ParsedBatterRecord first = records.get(0);
        ParsedBatterRecord replacement = new ParsedBatterRecord(
                first.teamSide(),
                first.sourceGroupIndex(),
                first.battingOrder(),
                first.position(),
                first.playerName(),
                atBats,
                runs,
                hits,
                rbi,
                homeRuns,
                walks,
                strikeouts,
                first.stolenBases(),
                first.groundedIntoDoublePlay(),
                first.errors(),
                first.battingAverage(),
                first.sourceOrder()
        );
        return replaceFirst(records, replacement);
    }

    private List<ParsedPitcherRecord> replaceFirstPitcher(List<ParsedPitcherRecord> records, Integer pitchCount, String era) {
        ParsedPitcherRecord first = records.get(0);
        ParsedPitcherRecord replacement = new ParsedPitcherRecord(
                first.teamSide(),
                first.sourceGroupIndex(),
                first.pitchingOrder(),
                first.playerName(),
                first.appearance(),
                first.decision(),
                first.wins(),
                first.losses(),
                first.saves(),
                first.inningsPitched(),
                first.battersFaced(),
                pitchCount,
                first.atBats(),
                first.hits(),
                first.homeRuns(),
                first.walksOrHitByPitch(),
                first.strikeouts(),
                first.runs(),
                first.earnedRuns(),
                new java.math.BigDecimal(era),
                first.sourceOrder()
        );
        return replaceFirst(records, replacement);
    }

    private <T> List<T> replaceFirst(List<T> records, T replacement) {
        java.util.ArrayList<T> values = new java.util.ArrayList<>(records);
        values.set(0, replacement);
        return values;
    }

    private String fixture() throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/kbo/boxscore-final.json"));
    }

    private static final class InMemoryGameBoxscoreRecordWriteRepository implements GameBoxscoreRecordWriteRepository {

        private final Map<RowKey, BatterRecordWriteRow> batterRows = new LinkedHashMap<>();
        private final Map<RowKey, PitcherRecordWriteRow> pitcherRows = new LinkedHashMap<>();

        @Override
        public int deleteBatterRecordsByGameId(UUID gameId) {
            int previousSize = batterRows.size();
            batterRows.entrySet().removeIf(entry -> entry.getKey().gameId().equals(gameId));
            return previousSize - batterRows.size();
        }

        @Override
        public int deletePitcherRecordsByGameId(UUID gameId) {
            int previousSize = pitcherRows.size();
            pitcherRows.entrySet().removeIf(entry -> entry.getKey().gameId().equals(gameId));
            return previousSize - pitcherRows.size();
        }

        @Override
        public int upsertBatterRecords(List<BatterRecordWriteRow> rows) {
            rows.forEach(row -> batterRows.put(new RowKey(row.gameId(), row.teamId(), row.sourceOrder()), row));
            return rows.size();
        }

        @Override
        public int upsertPitcherRecords(List<PitcherRecordWriteRow> rows) {
            rows.forEach(row -> pitcherRows.put(new RowKey(row.gameId(), row.teamId(), row.sourceOrder()), row));
            return rows.size();
        }
    }

    private record RowKey(UUID gameId, UUID teamId, int sourceOrder) {
    }
}
