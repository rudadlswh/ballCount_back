package com.kbo.crawlerapi.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DateSyncLockService {

    private static final String LIVE_SYNC_LOCK_NAMESPACE = "kbo-live-sync";
    private static final String DETAIL_REFRESH_LOCK_NAMESPACE = "kbo-detail-refresh";
    private static final Logger log = LoggerFactory.getLogger(DateSyncLockService.class);

    private final DataSource dataSource;
    private final Set<String> inMemoryLocks = ConcurrentHashMap.newKeySet();

    public DateSyncLockService() {
        this(null);
    }

    @Autowired
    public DateSyncLockService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public SyncLock tryLock(LocalDate date) {
        return tryLock(LIVE_SYNC_LOCK_NAMESPACE, date.toString());
    }

    public SyncLock tryDetailRefreshLock(LocalDate date, String phase) {
        return tryLock(DETAIL_REFRESH_LOCK_NAMESPACE, date + ":" + phase);
    }

    private SyncLock tryLock(String namespace, String key) {
        if (dataSource == null) {
            String lockKey = namespace + ":" + key;
            boolean acquired = inMemoryLocks.add(lockKey);
            return new SyncLock(acquired, () -> inMemoryLocks.remove(lockKey));
        }
        try {
            Connection connection = dataSource.getConnection();
            boolean acquired = tryPostgresLock(connection, namespace, key);
            if (!acquired) {
                connection.close();
                return SyncLock.notAcquired();
            }
            return new SyncLock(true, connection::close);
        } catch (Exception exception) {
            String lockKey = namespace + ":" + key;
            boolean acquired = inMemoryLocks.add(lockKey);
            return new SyncLock(acquired, () -> inMemoryLocks.remove(lockKey));
        }
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

    public record SyncLock(boolean acquired, ThrowingRunnable release) implements AutoCloseable {

        private static SyncLock notAcquired() {
            return new SyncLock(false, () -> {
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
