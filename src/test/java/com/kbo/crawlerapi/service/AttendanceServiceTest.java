package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kbo.crawlerapi.api.InvalidParameterException;
import com.kbo.crawlerapi.api.ResourceNotFoundException;
import com.kbo.crawlerapi.repository.AttendanceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttendanceServiceTest {

    @Test
    void upsertValidatesUuidInputs() {
        RecordingAttendanceRepository attendanceRepository = new RecordingAttendanceRepository();
        AttendanceService service = new AttendanceService(attendanceRepository);

        assertThatThrownBy(() -> service.upsert("not-a-uuid", UUID.randomUUID().toString()))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessage("installationId must be a UUID");

        org.assertj.core.api.Assertions.assertThat(attendanceRepository.upsertCount).isZero();
    }

    @Test
    void upsertRejectsMissingGame() {
        UUID installationId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        RecordingAttendanceRepository attendanceRepository = new RecordingAttendanceRepository();
        attendanceRepository.gameExists = false;
        AttendanceService service = new AttendanceService(attendanceRepository);

        assertThatThrownBy(() -> service.upsert(installationId.toString(), gameId.toString()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("game not found: " + gameId);

        org.assertj.core.api.Assertions.assertThat(attendanceRepository.upsertCount).isZero();
    }

    @Test
    void deleteUsesNormalizedUuidsWhenGameExists() {
        UUID installationId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        RecordingAttendanceRepository attendanceRepository = new RecordingAttendanceRepository();
        attendanceRepository.gameExists = true;
        AttendanceService service = new AttendanceService(attendanceRepository);

        service.delete(installationId.toString(), gameId.toString());

        org.assertj.core.api.Assertions.assertThat(attendanceRepository.deletedInstallationId).isEqualTo(installationId);
        org.assertj.core.api.Assertions.assertThat(attendanceRepository.deletedGameId).isEqualTo(gameId);
    }

    private static final class RecordingAttendanceRepository extends AttendanceRepository {
        private boolean gameExists;
        private int upsertCount;
        private UUID deletedInstallationId;
        private UUID deletedGameId;

        private RecordingAttendanceRepository() {
            super(null);
        }

        @Override
        public boolean gameExists(UUID gameId) {
            return gameExists;
        }

        @Override
        public void upsert(UUID installationId, UUID gameId) {
            upsertCount++;
        }

        @Override
        public void delete(UUID installationId, UUID gameId) {
            deletedInstallationId = installationId;
            deletedGameId = gameId;
        }

        @Override
        public List<UUID> findGameIds(UUID installationId) {
            return List.of();
        }
    }
}
