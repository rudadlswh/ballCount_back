package com.kbo.crawlerapi.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboScheduleParser.ParsedScheduleGame;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PublicGameWriteRepositoryTest {

    @Test
    void scheduledRefreshDoesNotOverwriteExistingCancelledGame() throws Exception {
        UUID homeTeamId = UUID.randomUUID();
        UUID awayTeamId = UUID.randomUUID();
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(homeTeamId, awayTeamId);
        PublicGameWriteRepository repository = new PublicGameWriteRepository(jdbcTemplate);
        Team lg = new Team(homeTeamId, "lg", "LG Twins", "LG", "LG Twins", null);
        Team hanwha = new Team(awayTeamId, "hanwha", "Hanwha Eagles", "Hanwha", "Hanwha Eagles", null);
        ParsedScheduleGame incomingScheduled = new ParsedScheduleGame(
                "kbo",
                "20260705LGHH0",
                LocalDate.of(2026, 7, 5),
                OffsetDateTime.of(2026, 7, 5, 18, 0, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                false,
                false,
                "한화",
                "LG",
                null,
                null,
                null,
                null,
                null
        );

        ScheduleGameWriteRepository.GameWriteResult result = repository.upsertScheduleGame(
                incomingScheduled,
                hanwha,
                lg,
                "20260705-LG-HAN",
                OffsetDateTime.of(2026, 7, 5, 17, 0, 0, 0, ZoneOffset.ofHours(9))
        );

        assertThat(result.updated()).isTrue();
        assertThat(jdbcTemplate.updateArgs[5]).isEqualTo("cancelled");
        assertThat(jdbcTemplate.updateArgs[10]).isEqualTo("우천취소");
        assertThat(jdbcTemplate.updateArgs[11]).isEqualTo(true);
        assertThat(jdbcTemplate.updateArgs[12]).isEqualTo(false);
        assertThat(jdbcTemplate.updateArgs[13]).isEqualTo("rain");
        assertThat(jdbcTemplate.updateArgs[14]).isEqualTo("우천취소");
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private final UUID homeTeamId;
        private final UUID awayTeamId;
        private Object[] updateArgs = new Object[0];

        private CapturingJdbcTemplate(UUID homeTeamId, UUID awayTeamId) {
            this.homeTeamId = homeTeamId;
            this.awayTeamId = awayTeamId;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            try {
                return List.of(rowMapper.mapRow(cancelledResultSet(homeTeamId, awayTeamId), 0));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            updateArgs = args;
            return 1;
        }
    }

    private static ResultSet cancelledResultSet(UUID homeTeamId, UUID awayTeamId) throws Exception {
        UUID gameId = UUID.randomUUID();
        OffsetDateTime previousUpdatedAt = OffsetDateTime.of(2026, 7, 5, 16, 0, 0, 0, ZoneOffset.ofHours(9));
        Map<String, Object> values = new HashMap<>();
        values.put("id", gameId);
        values.put("public_game_id", "20260705-LG-HAN");
        values.put("provider", "kbo");
        values.put("provider_game_id", "20260705LGHH0");
        values.put("game_date", LocalDate.of(2026, 7, 5));
        values.put("scheduled_at", OffsetDateTime.of(2026, 7, 5, 18, 0, 0, 0, ZoneOffset.ofHours(9)));
        values.put("stadium", "잠실");
        values.put("status", "cancelled");
        values.put("home_team_id", homeTeamId);
        values.put("away_team_id", awayTeamId);
        values.put("home_score", null);
        values.put("away_score", null);
        values.put("status_reason", "우천취소");
        values.put("is_cancelled", true);
        values.put("is_postponed", false);
        values.put("cancel_reason", GameCancelReason.RAIN.getApiValue());
        values.put("raw_cancel_text", "우천취소");
        values.put("away_starting_pitcher_name", null);
        values.put("home_starting_pitcher_name", null);
        values.put("source_updated_at", previousUpdatedAt);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> resultSetValue(method.getName(), args, values)
        );
    }

    private static Object resultSetValue(String methodName, Object[] args, Map<String, Object> values) {
        if ("getString".equals(methodName)) {
            Object value = values.get((String) args[0]);
            return value == null ? null : value.toString();
        }
        if ("getBoolean".equals(methodName)) {
            return Boolean.TRUE.equals(values.get((String) args[0]));
        }
        if ("getObject".equals(methodName)) {
            return values.get((String) args[0]);
        }
        if ("wasNull".equals(methodName)) {
            return false;
        }
        if ("toString".equals(methodName)) {
            return "cancelledResultSet";
        }
        throw new UnsupportedOperationException("Unsupported ResultSet method in test: " + methodName);
    }
}
