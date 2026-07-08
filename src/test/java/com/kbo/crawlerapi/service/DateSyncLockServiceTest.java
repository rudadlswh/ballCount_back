package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DateSyncLockServiceTest {

    @Test
    void releasesPostgresAdvisoryLockBeforeClosingConnection() {
        FakeJdbc jdbc = new FakeJdbc(true, true);
        DateSyncLockService service = new DateSyncLockService(jdbc.dataSource(), properties("postgres-session"));

        DateSyncLockService.SyncLock lock = service.tryLock(LocalDate.of(2026, 7, 8));

        assertThat(lock.acquired()).isTrue();

        lock.close();

        assertThat(jdbc.operations).containsExactly(
                "prepare:select pg_try_advisory_lock(hashtext(?), hashtext(?))",
                "execute:select pg_try_advisory_lock(hashtext(?), hashtext(?))",
                "prepare:select pg_advisory_unlock(hashtext(?), hashtext(?))",
                "execute:select pg_advisory_unlock(hashtext(?), hashtext(?))",
                "connection.close"
        );
        assertThat(jdbc.parameters).containsExactly(
                List.of("kbo-live-sync", "2026-07-08"),
                List.of("kbo-live-sync", "2026-07-08")
        );
    }

    @Test
    void returnsExternalHeldDurationWhenPostgresLockIsUnavailable() {
        FakeJdbc jdbc = new FakeJdbc(false, false);
        DateSyncLockService service = new DateSyncLockService(jdbc.dataSource(), properties("postgres-session"));

        DateSyncLockService.SyncLock lock = service.tryLock(LocalDate.of(2026, 7, 8));

        assertThat(lock.acquired()).isFalse();
        assertThat(lock.heldDurationMs()).isEqualTo(-1L);
        assertThat(jdbc.operations).containsExactly(
                "prepare:select pg_try_advisory_lock(hashtext(?), hashtext(?))",
                "execute:select pg_try_advisory_lock(hashtext(?), hashtext(?))",
                "connection.close"
        );
    }

    @Test
    void canAcquireSamePostgresLockAfterRelease() {
        FakeJdbc jdbc = new FakeJdbc(true, true);
        DateSyncLockService service = new DateSyncLockService(jdbc.dataSource(), properties("postgres-session"));

        DateSyncLockService.SyncLock first = service.tryLock(LocalDate.of(2026, 7, 8));
        first.close();
        DateSyncLockService.SyncLock second = service.tryLock(LocalDate.of(2026, 7, 8));
        second.close();

        assertThat(first.acquired()).isTrue();
        assertThat(second.acquired()).isTrue();
    }

    @Test
    void defaultMemoryModeDoesNotUsePostgresEvenWithDataSource() {
        FakeJdbc jdbc = new FakeJdbc(true, true);
        DateSyncLockService service = new DateSyncLockService(jdbc.dataSource());

        DateSyncLockService.SyncLock first = service.tryLock(LocalDate.of(2026, 7, 8));
        DateSyncLockService.SyncLock second = service.tryLock(LocalDate.of(2026, 7, 8));

        assertThat(first.acquired()).isTrue();
        assertThat(second.acquired()).isFalse();
        assertThat(second.heldDurationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(jdbc.operations).isEmpty();

        first.close();
        DateSyncLockService.SyncLock third = service.tryLock(LocalDate.of(2026, 7, 8));
        assertThat(third.acquired()).isTrue();
        third.close();
    }

    private static LiveSyncProperties properties(String lockMode) {
        LiveSyncProperties properties = new LiveSyncProperties();
        properties.setLockMode(lockMode);
        return properties;
    }

    private static final class FakeJdbc {

        private final boolean lockResult;
        private final boolean unlockResult;
        private final List<String> operations = new ArrayList<>();
        private final List<List<String>> parameters = new ArrayList<>();
        private boolean postgresHeld;

        private FakeJdbc(boolean lockResult, boolean unlockResult) {
            this.lockResult = lockResult;
            this.unlockResult = unlockResult;
        }

        private DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        if ("getConnection".equals(method.getName())) {
                            return connection();
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            String sql = (String) args[0];
                            operations.add("prepare:" + sql);
                            yield preparedStatement(sql);
                        }
                        case "close" -> {
                            operations.add("connection.close");
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        private PreparedStatement preparedStatement(String sql) {
            List<String> statementParameters = new ArrayList<>();
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setString" -> {
                            int index = (Integer) args[0];
                            while (statementParameters.size() < index) {
                                statementParameters.add(null);
                            }
                            statementParameters.set(index - 1, (String) args[1]);
                            yield null;
                        }
                        case "executeQuery" -> {
                            operations.add("execute:" + sql);
                            parameters.add(List.copyOf(statementParameters));
                            boolean result = sql.contains("pg_advisory_unlock")
                                    ? unlockResult && postgresHeld
                                    : lockResult && !postgresHeld;
                            if (sql.contains("pg_advisory_unlock") && result) {
                                postgresHeld = false;
                            } else if (!sql.contains("pg_advisory_unlock") && result) {
                                postgresHeld = true;
                            }
                            yield resultSet(result);
                        }
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        private ResultSet resultSet(boolean result) {
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    new java.lang.reflect.InvocationHandler() {
                        private boolean read;

                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                            return switch (method.getName()) {
                                case "next" -> {
                                    if (read) {
                                        yield false;
                                    }
                                    read = true;
                                    yield true;
                                }
                                case "getBoolean" -> result;
                                default -> defaultValue(method.getReturnType());
                            };
                        }
                    }
            );
        }

        private static Object defaultValue(Class<?> returnType) {
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            return null;
        }
    }
}
