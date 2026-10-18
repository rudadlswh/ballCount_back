package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kbo.crawlerapi.repository.NotificationEventRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses real migrations in a fresh schema on an explicitly supplied disposable local database. */
@EnabledIfEnvironmentVariable(named = "KBO_RETENTION_TEST_DATABASE_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:.*")
class DataRetentionServiceIntegrationTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-31T08:00:00+09:00");
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private String schema;
    private final UUID home = UUID.randomUUID(), away = UUID.randomUUID();
    private int sequence;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("KBO_RETENTION_TEST_DATABASE_URL");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "codex", ""));
        schema = "retention_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='anon') THEN CREATE ROLE anon; END IF; IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='authenticated') THEN CREATE ROLE authenticated; END IF; END $$");
        dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema, "codex", "");
        jdbc = new JdbcTemplate(dataSource);
        for (String migration : List.of("V1__init_core_schema.sql", "V7__add_live_sync_notifications.sql",
                "V15__add_live_activity_tokens.sql", "V38__persist_notification_delivery_work.sql",
                "V39__add_data_retention_support.sql")) {
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration", migration)));
        }
        jdbc.update("INSERT INTO teams(id,team_code,name,short_name) VALUES (?, 'home', 'Home', 'H'), (?, 'away', 'Away', 'A')", home, away);
    }

    @AfterEach
    void teardown() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void gameEventsRequireThreeWeeksSinceBothFinalConfirmationAndLastUpdate() {
        UUID oldGame = game("final", NOW.minusWeeks(3));
        UUID eligible = gameEvent(oldGame, NOW.minusWeeks(3));
        UUID recentlyUpdated = gameEvent(oldGame, NOW.minusWeeks(3).plusSeconds(1));
        UUID recentlyConfirmed = gameEvent(game("final", NOW.minusWeeks(3).plusSeconds(1)), NOW.minusMonths(2));
        UUID live = gameEvent(game("live", NOW.minusMonths(2)), NOW.minusMonths(2));
        UUID unconfirmed = gameEvent(game("final", null), NOW.minusMonths(2));
        jdbc.update("INSERT INTO game_snapshots(id,game_id) VALUES (?,?)", UUID.randomUUID(), oldGame);

        assertThat(cleanup(NOW).gameEvents()).isEqualTo(1);
        assertThat(ids("game_events")).doesNotContain(eligible)
                .containsExactlyInAnyOrder(recentlyUpdated, recentlyConfirmed, live, unconfirmed);
        assertThat(ids("game_snapshots")).hasSize(1);
        assertThat(ids("games")).hasSize(4);
    }

    @Test
    void inactiveTokensExpireAtEightKstAfterTheGameAndAmbiguousOrActiveTokensStay() {
        UUID yesterday = game("final", NOW.minusDays(1));
        UUID live = game("live", NOW.minusDays(1));
        UUID eligible = token(yesterday, false);
        UUID active = token(yesterday, true);
        UUID stillLive = token(live, false);
        UUID todayGame = game("final", NOW.minusMinutes(1));
        jdbc.update("UPDATE games SET game_date=? WHERE id=?", NOW.toLocalDate(), todayGame);
        UUID today = token(todayGame, false);
        UUID unconfirmed = token(game("final", null), false);
        UUID unknown = token(UUID.randomUUID(), false);
        UUID ambiguous = token(yesterday, false);
        jdbc.update("UPDATE live_activity_tokens SET public_game_id=? WHERE id=?", live.toString(), ambiguous);
        UUID aliasOnly = token(yesterday, false);
        jdbc.update("UPDATE live_activity_tokens SET database_id=NULL, stable_detail_identity=? WHERE id=?", "public:" + yesterday, aliasOnly);

        assertThat(cleanup(NOW.minusSeconds(1)).activityTokens()).isZero();
        assertThat(cleanup(NOW).activityTokens()).isEqualTo(2);
        assertThat(ids("live_activity_tokens")).doesNotContain(eligible, aliasOnly)
                .containsExactlyInAnyOrder(active, stillLive, today, unconfirmed, unknown, ambiguous);
    }

    @Test
    void notificationCleanupPreservesInflightWorkAndArchivesDeduplicationKeys() {
        UUID oldGame = game("final", NOW.minusWeeks(2));
        UUID eligible = notification(oldGame, "sent", NOW.minusWeeks(1));
        outbox(eligible, "done", NOW.minusWeeks(1), null);
        UUID device = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_devices(id,platform,device_token) VALUES (?, 'ios', 'device')", device);
        jdbc.update("INSERT INTO notification_deliveries(event_id,device_id,platform,status) VALUES (?,?,'ios','sent')", eligible, device);
        UUID pending = notification(oldGame, "failed", NOW.minusWeeks(2));
        outbox(pending, "pending", NOW.minusWeeks(2), null);
        UUID processing = notification(oldGame, "partial_failed", NOW.minusWeeks(2));
        outbox(processing, "processing", NOW.minusWeeks(2), NOW.minusDays(1));
        UUID leased = notification(oldGame, "sent", NOW.minusWeeks(2));
        outbox(leased, "done", NOW.minusWeeks(2), NOW.plusMinutes(1));
        UUID recentOutbox = notification(oldGame, "sent", NOW.minusWeeks(2));
        outbox(recentOutbox, "expired", NOW.minusWeeks(1).plusSeconds(1), null);
        UUID recentlySent = notification(oldGame, "sent", NOW.minusWeeks(2));
        jdbc.update("UPDATE notification_events SET sent_at=? WHERE id=?", NOW.minusDays(1), recentlySent);
        UUID queued = notification(oldGame, "queued", NOW.minusWeeks(2));
        UUID recent = notification(oldGame, "skipped", NOW.minusWeeks(1).plusSeconds(1));
        UUID notFinal = notification(game("live", NOW.minusWeeks(2)), "sent", NOW.minusWeeks(2));
        UUID recentFinal = notification(game("final", NOW.minusWeeks(1).plusSeconds(1)), "sent", NOW.minusWeeks(2));

        var result = cleanup(NOW);
        assertThat(result.outbox()).isEqualTo(1);
        assertThat(result.notifications()).isEqualTo(1);
        assertThat(ids("notification_events")).doesNotContain(eligible)
                .containsExactlyInAnyOrder(pending, processing, leased, recentOutbox, recentlySent, queued, recent, notFinal, recentFinal);
        assertThat(jdbc.queryForList("SELECT event_key FROM notification_event_keys", String.class)).containsExactly(eligible.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Integer.class)).isZero();
        assertThat(ids("notification_devices")).containsExactly(device);
        assertRepositoryFindsArchivedKey(eligible.toString());
        assertThat(cleanup(NOW).total()).isZero();
    }

    @Test
    void crawlRetentionUsesCalendarMonthsAndPreservesRunningOrRecentlyChangedWork() {
        OffsetDateTime boundary = NOW.minusMonths(2); // August 31, not 60 days before October 31.
        UUID eligible = job("succeeded", boundary, boundary);
        UUID eligibleFailure = failure(eligible, boundary);
        UUID recent = job("failed", boundary.plusSeconds(1), boundary);
        UUID updated = job("partial_success", boundary, NOW.minusDays(1));
        UUID running = job("running", boundary, boundary);
        UUID runningFailure = failure(running, boundary);
        UUID parentWithRecentFailure = job("failed", boundary, boundary);
        UUID recentFailure = failure(parentWithRecentFailure, boundary.plusSeconds(1));
        UUID orphan = failure(null, boundary);

        var result = cleanup(NOW);
        assertThat(result.jobs()).isEqualTo(1);
        assertThat(result.failures()).isEqualTo(2);
        assertThat(ids("crawl_jobs")).doesNotContain(eligible)
                .containsExactlyInAnyOrder(recent, updated, running, parentWithRecentFailure);
        assertThat(ids("crawl_failures")).doesNotContain(eligibleFailure, orphan)
                .containsExactlyInAnyOrder(runningFailure, recentFailure);
    }

    @Test
    void cleanupRollsBackDeletesAndArchivedKeysTogetherOnFailure() {
        UUID game = game("final", NOW.minusMonths(3));
        UUID event = gameEvent(game, NOW.minusMonths(2));
        UUID notification = notification(game, "sent", NOW.minusWeeks(2));
        job("failed", NOW.minusMonths(3), NOW.minusMonths(3));
        jdbc.execute("CREATE FUNCTION reject_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test rollback'; END $$");
        jdbc.execute("CREATE TRIGGER reject_delete BEFORE DELETE ON crawl_jobs FOR EACH ROW EXECUTE FUNCTION reject_delete()");

        assertThatThrownBy(() -> cleanup(NOW)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(ids("game_events")).containsExactly(event);
        assertThat(ids("notification_events")).containsExactly(notification);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_event_keys", Integer.class)).isZero();
    }

    @Test
    void concurrentCleanupIsSkippedAndBatchesAreBounded() throws Exception {
        UUID game = game("final", NOW.minusMonths(2));
        jdbc.update("INSERT INTO game_events(id,game_id,sequence_number,event_text,updated_at) SELECT gen_random_uuid(),?,n,'event',? FROM generate_series(1,1001) n", game, NOW.minusMonths(2));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(hashtext(current_schema()),hashtext('kbo-data-retention'))");
                assertThat(cleanup(NOW).total()).isZero();
            } finally {
                connection.rollback();
            }
        }
        assertThat(cleanup(NOW).gameEvents()).isEqualTo(1_000);
        assertThat(cleanup(NOW).gameEvents()).isEqualTo(1);
    }

    @Test
    void deduplicationKeysArePrivate() {
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid='notification_event_keys'::regclass", Boolean.class)).isTrue();
        for (String role : List.of("anon", "authenticated")) {
            assertThat(jdbc.queryForObject("SELECT has_table_privilege(?, 'notification_event_keys', 'SELECT,INSERT,UPDATE,DELETE')", Boolean.class, role)).isFalse();
        }
    }

    private DataRetentionService.CleanupResult cleanup(OffsetDateTime at) {
        var service = new DataRetentionService(jdbc, Clock.fixed(at.toInstant(), ZoneOffset.UTC));
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status -> service.cleanup());
    }

    private UUID game(String status, OffsetDateTime confirmedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO games(id,public_game_id,provider,provider_game_id,game_date,home_team_id,away_team_id,status,final_confirmed_at) VALUES (?,?,'kbo',?,?,?,?,?,?)",
                id, id.toString(), id.toString(), NOW.toLocalDate().minusDays(1), home, away, status, confirmedAt);
        return id;
    }

    private UUID gameEvent(UUID game, OffsetDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO game_events(id,game_id,sequence_number,event_text,updated_at) VALUES (?,?,?,'event',?)", id, game, ++sequence, updatedAt);
        return id;
    }

    private UUID token(UUID game, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO live_activity_tokens(id,activity_id,platform,environment,push_token,database_id,active) VALUES (?,?,'ios','production',?,?,?)", id, id.toString(), id.toString(), game.toString(), active);
        return id;
    }

    private UUID notification(UUID game, String status, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_events(id,game_id,event_type,event_key,title,body,payload,delivery_status,created_at,sent_at) VALUES (?,?,'SCORE_CHANGED',?,'title','body','{}',?,?,?)", id, game, id.toString(), status, createdAt, createdAt);
        return id;
    }

    private void outbox(UUID event, String status, OffsetDateTime expiresAt, OffsetDateTime leaseUntil) {
        jdbc.update("INSERT INTO notification_outbox(event_id,status,expires_at,lease_until) VALUES (?,?,?,?)", event, status, expiresAt, leaseUntil);
    }

    private UUID job(String status, OffsetDateTime finishedAt, OffsetDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO crawl_jobs(id,job_type,target_type,target_key,status,finished_at,updated_at) VALUES (?,'sync','game','game',?,?,?)", id, status, finishedAt, updatedAt);
        return id;
    }

    private UUID failure(UUID job, OffsetDateTime occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO crawl_failures(id,crawl_job_id,failure_stage,error_message,occurred_at) VALUES (?,?,'fetch','error',?)", id, job, occurredAt);
        return id;
    }

    private List<UUID> ids(String table) {
        return jdbc.queryForList("SELECT id FROM " + table, UUID.class);
    }

    private void assertRepositoryFindsArchivedKey(String key) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.kbo.crawlerapi.domain");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.afterPropertiesSet();
        try (var entityManager = factory.getObject().createEntityManager()) {
            var repository = new JpaRepositoryFactory(entityManager).getRepository(NotificationEventRepository.class);
            assertThat(repository.existsByEventKey(key)).isTrue();
            assertThat(repository.existsByEventKey("never-sent")).isFalse();
        } finally {
            factory.destroy();
        }
    }
}
