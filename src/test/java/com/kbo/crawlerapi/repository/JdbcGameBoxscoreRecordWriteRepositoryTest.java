package com.kbo.crawlerapi.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JdbcGameBoxscoreRecordWriteRepositoryTest {

    @Test
    void batterUpsertPreservesExistingBattingOrderAndPositionWhenIncomingValuesAreMissing() throws Exception {
        String sql = batterUpsertSql();

        assertThat(sql).contains("batting_order = COALESCE(excluded.batting_order, game_batter_records.batting_order)");
        assertThat(sql).contains("position = COALESCE(NULLIF(excluded.position, ''), game_batter_records.position)");
        assertThat(sql).contains("at_bats = excluded.at_bats");
        assertThat(sql).contains("runs = excluded.runs");
        assertThat(sql).contains("hits = excluded.hits");
        assertThat(sql).contains("rbi = excluded.rbi");
        assertThat(sql).contains("home_runs = excluded.home_runs");
        assertThat(sql).contains("walks = excluded.walks");
        assertThat(sql).contains("strikeouts = excluded.strikeouts");
    }

    @Test
    void publicGameBatterRecordsViewExposesBattingOrderAndPosition() throws Exception {
        String migrationSql = Files.readString(Path.of("src/main/resources/db/migration/V14__add_live_text_batter_extra_stats.sql"));

        assertThat(migrationSql).contains("CREATE OR REPLACE VIEW kbo_crawler_api.public_game_batter_records AS");
        assertThat(migrationSql).contains("source_order");
        assertThat(migrationSql).contains("batting_order");
        assertThat(migrationSql).contains("position");
        assertThat(migrationSql).contains("FROM kbo_crawler_api.game_batter_records");
    }

    private String batterUpsertSql() throws Exception {
        Field field = JdbcGameBoxscoreRecordWriteRepository.class.getDeclaredField("BATTER_UPSERT_SQL");
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
