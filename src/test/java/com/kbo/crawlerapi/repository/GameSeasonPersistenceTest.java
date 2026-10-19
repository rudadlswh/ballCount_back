package com.kbo.crawlerapi.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboScheduleParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs against an explicitly supplied disposable local PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = "KBO_SEASON_TEST_DATABASE_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:.*")
class GameSeasonPersistenceTest {
    @Test
    void changedStartTimeLinksTheOfficialGameToItsExistingProvisionalPublicId() throws Exception {
        String schema = "time_change_test_" + UUID.randomUUID().toString().replace("-", "");
        String baseUrl = System.getenv("KBO_SEASON_TEST_DATABASE_URL");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(baseUrl, "codex", ""));
        admin.execute("CREATE SCHEMA " + schema);
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(baseUrl + "?currentSchema=" + schema, "codex", ""));
        try {
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V1__init_core_schema.sql")));
            jdbc.execute("ALTER TABLE games ALTER COLUMN provider_game_id DROP NOT NULL, ADD COLUMN cancel_reason VARCHAR(30), ADD COLUMN raw_cancel_text TEXT, ADD COLUMN away_starting_pitcher_name VARCHAR(100), ADD COLUMN home_starting_pitcher_name VARCHAR(100), ADD COLUMN status_reason TEXT, ADD COLUMN final_confirmed_at TIMESTAMPTZ, ADD COLUMN live_last_checked_at TIMESTAMPTZ, ADD COLUMN official_provider_game_id TEXT");
            jdbc.execute("CREATE VIEW public_games AS SELECT id FROM games");
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V37__preserve_game_season_and_series.sql")).replace("${appSchema}", schema));
            Team away = new Team(UUID.randomUUID(), "lg", "LG", "LG", "LG", null);
            Team home = new Team(UUID.randomUUID(), "lotte", "롯데", "롯데", "Lotte", null);
            for (Team team : new Team[]{away, home}) {
                jdbc.update("INSERT INTO teams (id, team_code, name, short_name) VALUES (?, ?, ?, ?)", team.getId(), team.getTeamCode(), team.getName(), team.getShortName());
            }
            var writer = new PublicGameWriteRepository(jdbc);
            var date = java.time.LocalDate.of(2026, 10, 10);
            var at = OffsetDateTime.parse("2026-10-16T12:00:00+09:00");
            String publicId = "20261010-LOT-LG";
            var provisional = new KboScheduleParser.ParsedScheduleGame("kbo", null, date,
                    OffsetDateTime.parse("2026-10-10T17:00:00+09:00"), "사직", GameStatus.SCHEDULED,
                    false, false, "LG", "롯데", null, null, null, null, at);
            assertThat(writer.upsertScheduleGame(provisional, away, home, publicId, at).created()).isTrue();
            UUID originalId = jdbc.queryForObject("SELECT id FROM games WHERE public_game_id=?", UUID.class, publicId);
            UUID snapshotId = UUID.randomUUID();
            jdbc.update("INSERT INTO game_snapshots(id, game_id) VALUES (?, ?)", snapshotId, originalId);
            var official = new KboScheduleParser.ParsedScheduleGame("kbo", "20261010LGLT0", date,
                    OffsetDateTime.parse("2026-10-10T14:00:00+09:00"), "사직", GameStatus.FINAL,
                    false, false, "LG", "롯데", 4, 3, null, null, at).withSeriesId(0);
            var result = writer.upsertScheduleGame(official, away, home, publicId, at);
            assertThat(result.created()).isFalse();
            assertThat(result.updated()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM games", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT id, provider_game_id, series_id, status, away_score, home_score FROM games"))
                    .containsEntry("id", originalId).containsEntry("provider_game_id", "20261010LGLT0")
                    .containsEntry("series_id", 0).containsEntry("status", "final")
                    .containsEntry("away_score", 4).containsEntry("home_score", 3);
            assertThat(jdbc.queryForObject("SELECT scheduled_at FROM games", OffsetDateTime.class).toInstant())
                    .isEqualTo(official.scheduledAt().toInstant());
            assertThat(jdbc.queryForObject("SELECT game_id FROM game_snapshots WHERE id=?", UUID.class, snapshotId)).isEqualTo(originalId);
            assertThat(writer.upsertScheduleGame(official, away, home, publicId, at).updated()).isFalse();
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void migrationAndUpsertsPreserveAuthoritativeClassificationWithoutChangingGameIdentity() throws Exception {
        String schema = "season_test_" + UUID.randomUUID().toString().replace("-", "");
        String baseUrl = System.getenv("KBO_SEASON_TEST_DATABASE_URL");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(baseUrl, "codex", ""));
        admin.execute("CREATE SCHEMA " + schema);
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(baseUrl + "?currentSchema=" + schema, "codex", ""));
        try {
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V1__init_core_schema.sql")));
            jdbc.execute("ALTER TABLE games ALTER COLUMN provider_game_id DROP NOT NULL, ADD COLUMN cancel_reason VARCHAR(30), ADD COLUMN raw_cancel_text TEXT, ADD COLUMN away_starting_pitcher_name VARCHAR(100), ADD COLUMN home_starting_pitcher_name VARCHAR(100), ADD COLUMN status_reason TEXT, ADD COLUMN final_confirmed_at TIMESTAMPTZ, ADD COLUMN live_last_checked_at TIMESTAMPTZ, ADD COLUMN official_provider_game_id TEXT");
            jdbc.execute("CREATE VIEW public_games AS SELECT id FROM games");
            // Verify that existing rows remain unknown rather than being guessed by date.
            Team away = new Team(UUID.randomUUID(), "doosan", "두산", "두산", "Doosan", null);
            Team home = new Team(UUID.randomUUID(), "lg", "LG", "LG", "LG", null);
            for (Team team : new Team[]{away, home}) {
                jdbc.update("INSERT INTO teams (id, team_code, name, short_name) VALUES (?, ?, ?, ?)", team.getId(), team.getTeamCode(), team.getName(), team.getShortName());
            }
            jdbc.update("INSERT INTO games (id, public_game_id, provider, provider_game_id, game_date, home_team_id, away_team_id) VALUES (?, 'legacy', 'kbo', 'legacy', '2026-10-12', ?, ?)", UUID.randomUUID(), home.getId(), away.getId());
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V37__preserve_game_season_and_series.sql")).replace("${appSchema}", schema));
            assertThat(jdbc.queryForObject("SELECT season_type FROM games WHERE public_game_id = 'legacy'", String.class)).isEqualTo("unknown");

            var parser = new KboScheduleParser(new com.fasterxml.jackson.databind.ObjectMapper());
            var game = parser.parseMonthlySchedule(Files.readString(Path.of("src/test/resources/fixtures/kbo/schedule-postseason-202610.json")), YearMonth.of(2026, 10)).get(0);
            var writer = new PublicGameWriteRepository(jdbc);
            var at = OffsetDateTime.parse("2026-10-16T12:00:00+09:00");
            assertThat(writer.upsertScheduleGame(game, away, home, "postseason", at).created()).isTrue();
            UUID originalId = jdbc.queryForObject("SELECT id FROM games WHERE public_game_id = 'postseason'", UUID.class);
            assertThat(writer.upsertScheduleGame(game.withSeriesId(4), away, home, "postseason", at).updated()).isTrue();
            assertThat(writer.upsertScheduleGame(game.withSeriesId(4), away, home, "postseason", at).updated()).isFalse();
            writer.upsertScheduleGame(game.withStatus(GameStatus.LIVE), away, home, "postseason", at.plusSeconds(1));
            assertThat(jdbc.queryForMap("SELECT id, season_type, series_id FROM public_games WHERE public_game_id = 'postseason'"))
                    .containsEntry("id", originalId).containsEntry("season_type", "postseason").containsEntry("series_id", 4);
            String fixture = Files.readString(Path.of("src/test/resources/fixtures/kbo/schedule-postseason-202610.json"));
            for (int number : new int[]{1, 2}) {
                var doubleheader = parser.parseMonthlySchedule(fixture.replace("OBLG0", "OBLG" + number), YearMonth.of(2026, 10)).get(0).withSeriesId(0);
                assertThat(writer.upsertScheduleGame(doubleheader, away, home, "double-" + number, at).created()).isTrue();
                assertThat(writer.upsertScheduleGame(doubleheader, away, home, "changed-" + number, at).updated()).isFalse();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM games WHERE game_date = '2026-10-14'", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForList("SELECT provider_game_id FROM games WHERE public_game_id LIKE 'double-%'", String.class))
                    .containsExactlyInAnyOrder("20261014OBLG1", "20261014OBLG2");
            assertThatThrownBy(() -> jdbc.update("UPDATE games SET season_type = 'regular_season' WHERE public_game_id = 'postseason'"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE games SET series_id = NULL WHERE public_game_id = 'postseason'"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.execute("CREATE TABLE notification_events(id UUID PRIMARY KEY, delivery_status VARCHAR(30), error_message TEXT)");
            jdbc.execute("CREATE TABLE notification_devices(id UUID PRIMARY KEY)");
            admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='anon') THEN CREATE ROLE anon; END IF; IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='authenticated') THEN CREATE ROLE authenticated; END IF; END $$");
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V38__persist_notification_delivery_work.sql")));
            var outbox = new NotificationOutboxRepository(jdbc);
            UUID eventId = UUID.randomUUID(), sentDevice = UUID.randomUUID(), pendingDevice = UUID.randomUUID();
            jdbc.update("INSERT INTO notification_events(id, delivery_status) VALUES (?, 'queued')", eventId);
            for (UUID deviceId : new UUID[]{sentDevice, pendingDevice}) jdbc.update("INSERT INTO notification_devices(id) VALUES (?)", deviceId);
            var firstDevice = org.mockito.Mockito.mock(com.kbo.crawlerapi.domain.NotificationDevice.class);
            var secondDevice = org.mockito.Mockito.mock(com.kbo.crawlerapi.domain.NotificationDevice.class);
            org.mockito.Mockito.when(firstDevice.getId()).thenReturn(sentDevice);
            org.mockito.Mockito.when(secondDevice.getId()).thenReturn(pendingDevice);
            org.mockito.Mockito.when(firstDevice.getPlatform()).thenReturn("ios");
            org.mockito.Mockito.when(secondDevice.getPlatform()).thenReturn("android");
            outbox.enqueue(eventId, java.util.List.of(firstDevice, secondDevice), at);
            assertThat(outbox.due(at)).contains(eventId);
            UUID firstLease = outbox.claim(eventId, at);
            assertThat(firstLease).isNotNull();
            assertThat(outbox.claim(eventId, at)).isNull();
            outbox.record(eventId, firstLease, sentDevice, "sent", null, at);
            // Simulate a process crash after one device succeeds; a new process reclaims the expired lease.
            var restarted = new NotificationOutboxRepository(jdbc);
            UUID secondLease = restarted.claim(eventId, at.plusSeconds(61));
            assertThat(secondLease).isNotNull().isNotEqualTo(firstLease);
            assertThat(restarted.beforeSend(eventId, secondLease, sentDevice, at.plusSeconds(61))).isEqualTo("sent");
            assertThatThrownBy(() -> outbox.record(eventId, firstLease, pendingDevice, "sent", null, at.plusSeconds(61)))
                    .isInstanceOf(IllegalStateException.class);
            restarted.record(eventId, secondLease, pendingDevice, "failed", "timeout", at.plusSeconds(62));
            restarted.finish(eventId, secondLease, true, true, at.plusSeconds(62), null);
            assertThat(jdbc.queryForObject("SELECT delivery_status FROM notification_events WHERE id=?", String.class, eventId)).isEqualTo("partial_failed");
            assertThat(restarted.claim(eventId, at.plusSeconds(63))).isNull();
            UUID thirdLease = restarted.claim(eventId, at.plusSeconds(92));
            assertThat(thirdLease).isNotNull();
            restarted.record(eventId, thirdLease, pendingDevice, "sent", null, at.plusSeconds(92));
            restarted.finish(eventId, thirdLease, true, true, at.plusSeconds(92), null);
            assertThat(jdbc.queryForObject("SELECT delivery_status FROM notification_events WHERE id=?", String.class, eventId)).isEqualTo("sent");
            assertThat(restarted.due(at.plusMinutes(1))).doesNotContain(eventId);
            UUID staleEvent = UUID.randomUUID();
            jdbc.update("INSERT INTO notification_events(id, delivery_status) VALUES (?, 'queued')", staleEvent);
            restarted.enqueue(staleEvent, java.util.List.of(), at);
            assertThat(restarted.due(at.plusMinutes(6))).doesNotContain(staleEvent);
            assertThat(jdbc.queryForObject("SELECT delivery_status FROM notification_events WHERE id=?", String.class, staleEvent)).isEqualTo("expired");
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
