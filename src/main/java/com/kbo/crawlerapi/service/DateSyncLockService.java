package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DateSyncLockService {

    private static final String LIVE_SYNC_LOCK_NAMESPACE = "kbo-live-sync";
    private static final String DETAIL_REFRESH_LOCK_NAMESPACE = "kbo-detail-refresh";
    private static final String LOCK_MODE_MEMORY = "memory";
    private static final String LOCK_MODE_POSTGRES_SESSION = "postgres-session";
    private static final long STALE_LOCK_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final Logger log = LoggerFactory.getLogger(DateSyncLockService.class);

    private final DataSource dataSource;
    private final LiveSyncProperties properties;
    private final ConcurrentHashMap<String, Instant> lockStartedAtByKey = new ConcurrentHashMap<>();

    public DateSyncLockService() {
        this(null, null);
    }

    public DateSyncLockService(DataSource dataSource) {
        this(dataSource, null);
    }

    @Autowired
    public DateSyncLockService(DataSource dataSource, LiveSyncProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties == null ? new LiveSyncProperties() : properties;
    }

    public SyncLock tryLock(LocalDate date) {
        return tryLock(LIVE_SYNC_LOCK_NAMESPACE, date.toString());
    }

    public SyncLock tryDetailRefreshLock(LocalDate date, String phase) {
        return tryLock(DETAIL_REFRESH_LOCK_NAMESPACE, date + ":" + phase);
    }

    private SyncLock tryLock(String namespace, String key) {
        String lockKey = lockKey(namespace, key);
        String lockMode = lockMode();
        if (!LOCK_MODE_POSTGRES_SESSION.equals(lockMode) || dataSource == null) {
            return tryInMemoryLock(namespace, key, lockKey, lockMode);
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            boolean acquired = tryPostgresLock(connection, namespace, key);
            if (!acquired) {
                long heldDurationMs = heldDurationMs(lockKey, Instant.now());
                connection.close();
                if (heldDurationMs == -1L) {
                    log.warn(
                            "[DateSyncLock] lock unavailable namespace={} key={} lockMode={} source=postgres heldDurationMs={} reason=held_by_external_or_pooled_session",
                            namespace,
                            key,
                            lockMode,
                            heldDurationMs
                    );
                }
                return SyncLock.notAcquired(heldDurationMs);
            }
            Connection lockConnection = connection;
            connection = null;
            Instant startedAt = Instant.now();
            lockStartedAtByKey.put(lockKey, startedAt);
            return new SyncLock(true, 0L, () -> {
                try {
                    boolean unlocked = unlockPostgresLock(lockConnection, namespace, key);
                    log.info(
                            "[DateSyncLock] released namespace={} key={} lockMode={} source=postgres unlocked={}",
                            namespace,
                            key,
                            lockMode,
                            unlocked
                    );
                } finally {
                    try {
                        lockConnection.close();
                    } finally {
                        lockStartedAtByKey.remove(lockKey, startedAt);
                    }
                }
            });
        } catch (Exception exception) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception closeException) {
                    log.warn("Failed to close date sync lock connection.", closeException);
                }
            }
            return tryInMemoryLock(namespace, key, lockKey, lockMode);
        }
    }

    private SyncLock tryInMemoryLock(String namespace, String key, String lockKey, String lockMode) {
        Instant now = Instant.now();
        Instant existingStartedAt = lockStartedAtByKey.putIfAbsent(lockKey, now);
        if (existingStartedAt == null) {
            log.info("[DateSyncLock] acquired namespace={} key={} lockMode={} source=memory", namespace, key, lockMode);
            return new SyncLock(true, 0L, () -> {
                lockStartedAtByKey.remove(lockKey, now);
                log.info("[DateSyncLock] released namespace={} key={} lockMode={} source=memory", namespace, key, lockMode);
            });
        }

        long heldDurationMs = Duration.between(existingStartedAt, now).toMillis();
        if (heldDurationMs >= STALE_LOCK_TTL_MILLIS && lockStartedAtByKey.replace(lockKey, existingStartedAt, now)) {
            log.warn(
                    "[DateSyncLock] stale lock reclaimed namespace={} key={} lockMode={} source=memory heldDurationMs={}",
                    namespace,
                    key,
                    lockMode,
                    heldDurationMs
            );
            return new SyncLock(true, 0L, () -> {
                lockStartedAtByKey.remove(lockKey, now);
                log.info("[DateSyncLock] released namespace={} key={} lockMode={} source=memory", namespace, key, lockMode);
            });
        }
        log.info(
                "[DateSyncLock] lock unavailable namespace={} key={} lockMode={} source=memory heldDurationMs={}",
                namespace,
                key,
                lockMode,
                heldDurationMs
        );
        return SyncLock.notAcquired(heldDurationMs);
    }

    private String lockMode() {
        String configured = properties.getLockMode();
        if (configured == null || configured.isBlank()) {
            return LOCK_MODE_MEMORY;
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if (LOCK_MODE_POSTGRES_SESSION.equals(normalized)) {
            return LOCK_MODE_POSTGRES_SESSION;
        }
        if (!LOCK_MODE_MEMORY.equals(normalized)) {
            log.warn("[DateSyncLock] unsupported lockMode={} using={}", configured, LOCK_MODE_MEMORY);
        }
        return LOCK_MODE_MEMORY;
    }

    private String lockKey(String namespace, String key) {
        return namespace + ":" + key;
    }

    private long heldDurationMs(String lockKey, Instant now) {
        Instant startedAt = lockStartedAtByKey.get(lockKey);
        return startedAt == null ? -1L : Duration.between(startedAt, now).toMillis();
    }

    private boolean tryPostgresLock(Connection connection, String namespace, String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(hashtext(?), hashtext(?))")) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    boolean unlockPostgresLock(Connection connection, String namespace, String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(hashtext(?), hashtext(?))")) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    public record SyncLock(boolean acquired, long heldDurationMs, ThrowingRunnable release) implements AutoCloseable {

        private static SyncLock notAcquired(long heldDurationMs) {
            return new SyncLock(false, heldDurationMs, () -> {
            });
        }

        @Override
        public void close() {
            try {
                release.run();
            } catch (Exception exception) {
                log.warn("Failed to release date sync lock.", exception);
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
