package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGameBoxscoreRecordWriteRepository implements GameBoxscoreRecordWriteRepository {

    private static final String BATTER_UPSERT_SQL = """
            INSERT INTO game_batter_records (
                id,
                game_id,
                team_id,
                source_order,
                batting_order,
                position,
                player_name,
                at_bats,
                runs,
                hits,
                rbi,
                home_runs,
                walks,
                strikeouts,
                stolen_bases,
                grounded_into_double_play,
                errors,
                batting_average,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (game_id, team_id, source_order) DO UPDATE SET
                batting_order = COALESCE(excluded.batting_order, game_batter_records.batting_order),
                position = COALESCE(NULLIF(excluded.position, ''), game_batter_records.position),
                player_name = excluded.player_name,
                at_bats = excluded.at_bats,
                runs = excluded.runs,
                hits = excluded.hits,
                rbi = excluded.rbi,
                home_runs = excluded.home_runs,
                walks = excluded.walks,
                strikeouts = excluded.strikeouts,
                stolen_bases = excluded.stolen_bases,
                grounded_into_double_play = excluded.grounded_into_double_play,
                errors = excluded.errors,
                batting_average = excluded.batting_average,
                updated_at = now()
            """;

    private static final String PITCHER_UPSERT_SQL = """
            INSERT INTO game_pitcher_records (
                id,
                game_id,
                team_id,
                source_order,
                pitching_order,
                player_name,
                appearance,
                decision_result,
                wins,
                losses,
                saves,
                innings_pitched,
                batters_faced,
                pitch_count,
                at_bats,
                hits,
                home_runs,
                walks_or_hit_by_pitch,
                strikeouts,
                runs,
                earned_runs,
                era,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (game_id, team_id, source_order) DO UPDATE SET
                pitching_order = excluded.pitching_order,
                player_name = excluded.player_name,
                appearance = excluded.appearance,
                decision_result = excluded.decision_result,
                wins = excluded.wins,
                losses = excluded.losses,
                saves = excluded.saves,
                innings_pitched = excluded.innings_pitched,
                batters_faced = excluded.batters_faced,
                pitch_count = excluded.pitch_count,
                at_bats = excluded.at_bats,
                hits = excluded.hits,
                home_runs = excluded.home_runs,
                walks_or_hit_by_pitch = excluded.walks_or_hit_by_pitch,
                strikeouts = excluded.strikeouts,
                runs = excluded.runs,
                earned_runs = excluded.earned_runs,
                era = excluded.era,
                updated_at = now()
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcGameBoxscoreRecordWriteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int upsertBatterRecords(List<BatterRecordWriteRow> rows) {
        int[][] results = jdbcTemplate.batchUpdate(BATTER_UPSERT_SQL, rows, 50, (ps, row) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, row.gameId());
            ps.setObject(3, row.teamId());
            ps.setInt(4, row.sourceOrder());
            ps.setObject(5, row.battingOrder());
            ps.setString(6, row.position());
            ps.setString(7, row.playerName());
            ps.setObject(8, row.atBats());
            ps.setObject(9, row.runs());
            ps.setObject(10, row.hits());
            ps.setObject(11, row.rbi());
            ps.setObject(12, row.homeRuns());
            ps.setObject(13, row.walks());
            ps.setObject(14, row.strikeouts());
            ps.setObject(15, row.stolenBases());
            ps.setObject(16, row.groundedIntoDoublePlay());
            ps.setObject(17, row.errors());
            ps.setString(18, row.battingAverage());
        });
        return count(results);
    }

    @Override
    public int upsertPitcherRecords(List<PitcherRecordWriteRow> rows) {
        int[][] results = jdbcTemplate.batchUpdate(PITCHER_UPSERT_SQL, rows, 50, (ps, row) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, row.gameId());
            ps.setObject(3, row.teamId());
            ps.setInt(4, row.sourceOrder());
            ps.setObject(5, row.pitchingOrder());
            ps.setString(6, row.playerName());
            ps.setString(7, row.appearance());
            ps.setString(8, row.decisionResult());
            ps.setObject(9, row.wins());
            ps.setObject(10, row.losses());
            ps.setObject(11, row.saves());
            ps.setString(12, row.inningsPitched());
            ps.setObject(13, row.battersFaced());
            ps.setObject(14, row.pitchCount());
            ps.setObject(15, row.atBats());
            ps.setObject(16, row.hits());
            ps.setObject(17, row.homeRuns());
            ps.setObject(18, row.walksOrHitByPitch());
            ps.setObject(19, row.strikeouts());
            ps.setObject(20, row.runs());
            ps.setObject(21, row.earnedRuns());
            ps.setString(22, row.era());
        });
        return count(results);
    }

    private int count(int[][] results) {
        int count = 0;
        for (int[] batch : results) {
            for (int result : batch) {
                count += Math.max(result, 0);
            }
        }
        return count;
    }
}
