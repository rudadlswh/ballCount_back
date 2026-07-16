package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedLineupData;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedLineupPlayer;
import com.kbo.crawlerapi.parser.KboLiveTextParser;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository.BatterRecordReadRow;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository.PitcherRecordReadRow;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordWriteRepository;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordWriteRepository.BatterRecordWriteRow;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordWriteRepository.PitcherRecordWriteRow;
import com.kbo.crawlerapi.repository.GameEventWriteRepository;
import com.kbo.crawlerapi.repository.GameEventWriteRepository.GameEventWriteRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameLiveTextRecordServiceTest {

    private InMemoryGameBoxscoreRecordWriteRepository boxscoreRepository;
    private InMemoryGameEventWriteRepository eventRepository;
    private GameLiveTextRecordService service;
    private Team awayTeam;
    private Team homeTeam;
    private Game liveGame;

    @BeforeEach
    void setUp() {
        boxscoreRepository = new InMemoryGameBoxscoreRecordWriteRepository();
        eventRepository = new InMemoryGameEventWriteRepository();
        service = new GameLiveTextRecordService(
                new GameBoxscoreRecordService(boxscoreRepository),
                boxscoreRepository,
                eventRepository
        );
        awayTeam = new Team(UUID.randomUUID(), "lotte", "롯데 자이언츠", "롯데", "Lotte Giants", null);
        homeTeam = new Team(UUID.randomUUID(), "nc", "NC 다이노스", "NC", "NC Dinos", null);
        liveGame = game(GameStatus.LIVE);
    }

    @Test
    void savesLiveTextBatterPitcherRecordsAndGameEvents() {
        var parsed = parsedLiveText();

        var result = service.saveLiveText(liveGame, parsed, OffsetDateTime.now());

        assertThat(result.batterRecordCount()).isEqualTo(2);
        assertThat(result.pitcherRecordCount()).isEqualTo(2);
        assertThat(result.eventCount()).isEqualTo(2);
        assertThat(boxscoreRepository.batterRows).hasSize(2);
        assertThat(boxscoreRepository.pitcherRows).hasSize(2);
        assertThat(eventRepository.eventRows).hasSize(2);
        assertThat(boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), awayTeam.getId(), 0)).playerName()).isEqualTo("박승욱");
        assertThat(boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), homeTeam.getId(), 0)).groundedIntoDoublePlay()).isEqualTo(1);
        assertThat(boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), homeTeam.getId(), 0)).errors()).isEqualTo(1);
        assertThat(boxscoreRepository.pitcherRows.get(new RowKey(liveGame.getId(), homeTeam.getId(), 0)).playerName()).isEqualTo("전사민");
        assertThat(eventRepository.eventRows.get(new EventKey(liveGame.getId(), 1)).eventType()).isEqualTo("STRIKEOUT");
    }

    @Test
    void repeatedLiveTextSyncIsIdempotent() {
        var parsed = parsedLiveText();

        service.saveLiveText(liveGame, parsed, OffsetDateTime.now());
        service.saveLiveText(liveGame, parsed, OffsetDateTime.now());

        assertThat(boxscoreRepository.batterRows).hasSize(2);
        assertThat(boxscoreRepository.pitcherRows).hasSize(2);
        assertThat(eventRepository.eventRows).hasSize(2);
    }

    @Test
    void finalGamesDoNotOverwriteOfficialBoxscoreRecordsWithLiveTextRecords() {
        Game finalGame = game(GameStatus.FINAL);

        var result = service.saveLiveText(finalGame, parsedLiveText(), OffsetDateTime.now());

        assertThat(result.batterRecordCount()).isZero();
        assertThat(result.pitcherRecordCount()).isZero();
        assertThat(result.eventCount()).isEqualTo(2);
        assertThat(boxscoreRepository.batterRows).isEmpty();
        assertThat(boxscoreRepository.pitcherRows).isEmpty();
        assertThat(eventRepository.eventRows).hasSize(2);
    }

    @Test
    void fillsMissingLiveTextBattingOrderFromSourceOrder() {
        var parsed = new KboLiveTextParser.ParsedLiveText(
                List.of(new KboLiveTextParser.ParsedLiveTextBatterRecord("away", 0, null, null, "박승욱", 4, 1, 2, 1, 0, 1, 0, 1, 0, 0, 0, 3)),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        service.saveLiveText(liveGame, parsed, OffsetDateTime.now());

        BatterRecordWriteRow saved = boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), awayTeam.getId(), 3));
        assertThat(saved.battingOrder()).isEqualTo(4);
        assertThat(saved.position()).isNull();
    }

    @Test
    void mergesLineupPositionIntoLiveTextBatterRecords() {
        var parsed = new KboLiveTextParser.ParsedLiveText(
                List.of(
                        new KboLiveTextParser.ParsedLiveTextBatterRecord("away", 0, 1, null, "순번매칭", 4, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0),
                        new KboLiveTextParser.ParsedLiveTextBatterRecord("away", 0, null, null, "김 이름", 3, 0, 1, 1, 0, 1, 0, 0, 0, 0, 0, 4),
                        new KboLiveTextParser.ParsedLiveTextBatterRecord("away", 0, null, null, "소스순서매칭", 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var lineupData = new ParsedLineupData(
                List.of(
                        new ParsedLineupPlayer("1", "CF", "라인업1"),
                        new ParsedLineupPlayer("8", "SS", "라인업2"),
                        new ParsedLineupPlayer("7", "RF", "김이름")
                ),
                List.of(),
                "lineup-hash"
        );

        service.saveLiveText(liveGame, parsed, OffsetDateTime.now(), lineupData);

        assertThat(boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), awayTeam.getId(), 0)).position()).isEqualTo("CF");
        assertThat(boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), awayTeam.getId(), 4)).position()).isEqualTo("RF");
        assertThat(boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), awayTeam.getId(), 1)).position()).isEqualTo("SS");
        assertThat(boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), awayTeam.getId(), 4)).battingOrder()).isEqualTo(5);
        assertThat(boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), awayTeam.getId(), 1)).battingOrder()).isEqualTo(2);
    }

    @Test
    void keepsExistingLiveTextPositionAndDoesNotOverwriteStoredPositionWithNull() {
        var initial = new KboLiveTextParser.ParsedLiveText(
                List.of(new KboLiveTextParser.ParsedLiveTextBatterRecord("away", 0, 1, "LF", "박승욱", 4, 1, 2, 1, 0, 1, 0, 1, 0, 0, 0, 0)),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var withoutPosition = new KboLiveTextParser.ParsedLiveText(
                List.of(new KboLiveTextParser.ParsedLiveTextBatterRecord("away", 0, 1, null, "박승욱", 5, 2, 3, 2, 1, 0, 1, 0, 0, 0, 0, 0)),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        service.saveLiveText(liveGame, initial, OffsetDateTime.now());
        service.saveLiveText(liveGame, withoutPosition, OffsetDateTime.now());

        BatterRecordWriteRow saved = boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), awayTeam.getId(), 0));
        assertThat(saved.position()).isEqualTo("LF");
        assertThat(saved.atBats()).isEqualTo(5);
        assertThat(saved.runs()).isEqualTo(2);
        assertThat(saved.hits()).isEqualTo(3);
        assertThat(saved.rbi()).isEqualTo(2);
        assertThat(saved.homeRuns()).isEqualTo(1);
        assertThat(saved.strikeouts()).isEqualTo(1);
    }

    @Test
    void keepsExistingLiveTextPositionWhenIncomingPositionIsEmpty() {
        savePositionUpdates("LF", "");

        assertThat(savedAwayBatter().position()).isEqualTo("LF");
    }

    @Test
    void keepsExistingLiveTextPositionWhenIncomingPositionIsWhitespace() {
        savePositionUpdates("LF", "   ");

        assertThat(savedAwayBatter().position()).isEqualTo("LF");
    }

    @Test
    void updatesExistingLiveTextPositionWhenIncomingPositionHasText() {
        savePositionUpdates("LF", "RF");

        assertThat(savedAwayBatter().position()).isEqualTo("RF");
    }

    @Test
    void mergesPositionWhenOfficialTeamIdsDifferFromDatabaseTeamCodes() {
        Team ssgTeam = new Team(UUID.randomUUID(), "ssg", "SSG 랜더스", "SSG", "SSG Landers", null);
        Game lotteAtSsg = game(GameStatus.LIVE, ssgTeam, awayTeam);
        var parsed = new KboLiveTextParser.ParsedLiveText(
                List.of(new KboLiveTextParser.ParsedLiveTextBatterRecord("away", 0, 1, null, "황성빈", 1, 1, 0, 0, 0, 2, 0, 1, 0, 0, 0, 0)),
                List.of(new KboLiveTextParser.ParsedLiveTextBatterRecord("home", 1, 1, null, "박성한", 2, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0)),
                List.of(),
                List.of(),
                List.of()
        );
        var lineupData = new ParsedLineupData(
                List.of(new ParsedLineupPlayer("1", "CF", "황성빈", "LT")),
                List.of(new ParsedLineupPlayer("1", "SS", "박성한", "SK")),
                "lineup-hash",
                "LT",
                "SK"
        );

        service.saveLiveText(lotteAtSsg, parsed, OffsetDateTime.now(), lineupData);

        assertThat(boxscoreRepository.batterRows.get(new RowKey(lotteAtSsg.getId(), awayTeam.getId(), 0)).position()).isEqualTo("CF");
        assertThat(boxscoreRepository.batterRows.get(new RowKey(lotteAtSsg.getId(), ssgTeam.getId(), 0)).position()).isEqualTo("SS");
    }

    @Test
    void updatesPreviouslyNullPositionWhenMergedPositionIsNonNull() {
        var initial = new KboLiveTextParser.ParsedLiveText(
                List.of(new KboLiveTextParser.ParsedLiveTextBatterRecord("away", 0, 1, null, "황성빈", 1, 1, 0, 0, 0, 2, 0, 1, 0, 0, 0, 0)),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var lineupData = new ParsedLineupData(
                List.of(new ParsedLineupPlayer("1", "CF", "황성빈", "LT")),
                List.of(),
                "lineup-hash",
                "LT",
                null
        );

        service.saveLiveText(liveGame, initial, OffsetDateTime.now());
        service.saveLiveText(liveGame, initial, OffsetDateTime.now(), lineupData);

        BatterRecordWriteRow saved = boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), awayTeam.getId(), 0));
        assertThat(saved.position()).isEqualTo("CF");
    }

    private void savePositionUpdates(String existingPosition, String incomingPosition) {
        service.saveLiveText(liveGame, liveTextWithAwayPosition(existingPosition), OffsetDateTime.now());
        service.saveLiveText(liveGame, liveTextWithAwayPosition(incomingPosition), OffsetDateTime.now());
    }

    private KboLiveTextParser.ParsedLiveText liveTextWithAwayPosition(String position) {
        return new KboLiveTextParser.ParsedLiveText(
                List.of(new KboLiveTextParser.ParsedLiveTextBatterRecord(
                        "away", 0, 1, position, "박승욱", 5, 2, 3, 2, 1, 0, 1, 0, 0, 0, 0, 0
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private BatterRecordWriteRow savedAwayBatter() {
        return boxscoreRepository.batterRows.get(new RowKey(liveGame.getId(), awayTeam.getId(), 0));
    }

    private KboLiveTextParser.ParsedLiveText parsedLiveText() {
        return new KboLiveTextParser.ParsedLiveText(
                List.of(new KboLiveTextParser.ParsedLiveTextBatterRecord("away", 0, 1, "유", "박승욱", 4, 1, 2, 1, 0, 1, 0, 1, 0, 0, 0, 0)),
                List.of(new KboLiveTextParser.ParsedLiveTextBatterRecord("home", 1, 1, "유", "김주원", 5, 2, 3, 4, 1, 2, 1, 0, 0, 1, 1, 0)),
                List.of(new KboLiveTextParser.ParsedLiveTextPitcherRecord("away", 0, 1, "박세웅", "선발", null, "6", 24, 88, 22, 5, 0, 1, 1, 6, 1, 1, 0)),
                List.of(new KboLiveTextParser.ParsedLiveTextPitcherRecord("home", 1, 1, "전사민", "선발", null, "2", 12, 46, 8, 3, 1, 0, 3, 0, 3, 2, 0)),
                List.of(
                        new KboLiveTextParser.ParsedLiveTextEvent(1, 1, "top", "STRIKEOUT", "김주원 : 삼진 아웃", "김주원"),
                        new KboLiveTextParser.ParsedLiveTextEvent(2, 1, "top", "HIT", "박승욱 : 중견수 앞 1루타", "박승욱")
                )
        );
    }

    private Game game(GameStatus status) {
        return game(status, homeTeam, awayTeam);
    }

    private Game game(GameStatus status, Team homeTeam, Team awayTeam) {
        return new Game(
                UUID.randomUUID(),
                "20260529-LOT-NC",
                "kbo",
                "20260529LTNC0",
                LocalDate.of(2026, 5, 29),
                OffsetDateTime.of(2026, 5, 29, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "창원",
                status,
                homeTeam,
                awayTeam,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null
        );
    }

    private static final class InMemoryGameBoxscoreRecordWriteRepository
            implements GameBoxscoreRecordWriteRepository, GameBoxscoreRecordReadRepository {

        private final Map<RowKey, BatterRecordWriteRow> batterRows = new LinkedHashMap<>();
        private final Map<RowKey, PitcherRecordWriteRow> pitcherRows = new LinkedHashMap<>();

        @Override
        public List<BatterRecordReadRow> findBatterRecords(UUID gameId) {
            return batterRows.values().stream()
                    .filter(row -> row.gameId().equals(gameId))
                    .map(row -> new BatterRecordReadRow(
                            row.teamId(),
                            row.sourceOrder(),
                            row.battingOrder(),
                            row.position(),
                            row.playerName(),
                            row.atBats(),
                            row.runs(),
                            row.hits(),
                            row.rbi(),
                            row.homeRuns(),
                            row.walks(),
                            row.strikeouts(),
                            row.stolenBases(),
                            row.groundedIntoDoublePlay(),
                            row.errors(),
                            row.battingAverage(),
                            null
                    ))
                    .toList();
        }

        @Override
        public List<PitcherRecordReadRow> findPitcherRecords(UUID gameId) {
            return List.of();
        }

        @Override
        public long countBatterRecords(UUID gameId) {
            return findBatterRecords(gameId).size();
        }

        @Override
        public long countPitcherRecords(UUID gameId) {
            return pitcherRows.keySet().stream().filter(key -> key.gameId().equals(gameId)).count();
        }

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
            rows.forEach(row -> {
                RowKey key = new RowKey(row.gameId(), row.teamId(), row.sourceOrder());
                BatterRecordWriteRow existing = batterRows.get(key);
                batterRows.put(key, merge(existing, row));
            });
            return rows.size();
        }

        private BatterRecordWriteRow merge(BatterRecordWriteRow existing, BatterRecordWriteRow incoming) {
            if (existing == null) {
                return incoming;
            }
            return new BatterRecordWriteRow(
                    incoming.gameId(),
                    incoming.teamId(),
                    incoming.sourceOrder(),
                    incoming.battingOrder() == null ? existing.battingOrder() : incoming.battingOrder(),
                    incoming.position() == null || incoming.position().isBlank() ? existing.position() : incoming.position(),
                    incoming.playerName(),
                    incoming.atBats(),
                    incoming.runs(),
                    incoming.hits(),
                    incoming.rbi(),
                    incoming.homeRuns(),
                    incoming.walks(),
                    incoming.strikeouts(),
                    incoming.stolenBases(),
                    incoming.groundedIntoDoublePlay(),
                    incoming.errors(),
                    incoming.battingAverage()
            );
        }

        @Override
        public int upsertPitcherRecords(List<PitcherRecordWriteRow> rows) {
            rows.forEach(row -> pitcherRows.put(new RowKey(row.gameId(), row.teamId(), row.sourceOrder()), row));
            return rows.size();
        }
    }

    private static final class InMemoryGameEventWriteRepository implements GameEventWriteRepository {

        private final Map<EventKey, GameEventWriteRow> eventRows = new LinkedHashMap<>();

        @Override
        public int upsertEvents(List<GameEventWriteRow> rows) {
            rows.forEach(row -> eventRows.put(new EventKey(row.gameId(), row.sequenceNumber()), row));
            return rows.size();
        }
    }

    private record RowKey(UUID gameId, UUID teamId, int sourceOrder) {
    }

    private record EventKey(UUID gameId, int sequenceNumber) {
    }
}
