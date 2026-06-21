package com.kbo.crawlerapi.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DateSyncLockService {

    private static final String LOCK_NAMESPACE = "kbo-live-sync";

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
        String key = date.toString();
        if (dataSource == null) {
            boolean acquired = inMemoryLocks.add(key);
            return new SyncLock(acquired, () -> inMemoryLocks.remove(key));
        }
        try {
            Connection connection = dataSource.getConnection();
            boolean acquired = tryPostgresLock(connection, key);
            if (!acquired) {
                connection.close();
                return SyncLock.notAcquired();
            }
            return new SyncLock(true, connection::close);
        } catch (Exception exception) {
            boolean acquired = inMemoryLocks.add(key);
            return new SyncLock(acquired, () -> inMemoryLocks.remove(key));
        }
    }

    private boolean tryPostgresLock(Connection connection, String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(hashtext(?), hashtext(?))")) {
            statement.setString(1, LOCK_NAMESPACE);
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
            } catch (Exception ignored) {
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
