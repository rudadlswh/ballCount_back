package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kbo.crawlerapi.api.InvalidParameterException;
import com.kbo.crawlerapi.api.ResourceNotFoundException;
import com.kbo.crawlerapi.repository.AttendanceRepository;
import com.kbo.crawlerapi.repository.AttendanceGameRecordRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
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
        attendanceRepository.resolvedGameId = gameId;
        AttendanceService service = new AttendanceService(attendanceRepository);

        service.delete(installationId.toString(), gameId.toString());

        org.assertj.core.api.Assertions.assertThat(attendanceRepository.deletedInstallationId).isEqualTo(installationId);
        org.assertj.core.api.Assertions.assertThat(attendanceRepository.deletedGameId).isEqualTo(gameId);
    }

    @Test
    void deleteNormalizesUppercaseGameUuidBeforeLookup() {
        UUID installationId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        RecordingAttendanceRepository attendanceRepository = new RecordingAttendanceRepository();
        attendanceRepository.resolvedGameId = gameId;
        AttendanceService service = new AttendanceService(attendanceRepository);

        service.delete(installationId.toString(), gameId.toString().toUpperCase());

        org.assertj.core.api.Assertions.assertThat(attendanceRepository.lastResolvedIdentifier)
                .isEqualTo(gameId.toString());
        org.assertj.core.api.Assertions.assertThat(attendanceRepository.deletedGameId).isEqualTo(gameId);
    }

    @Test
    void upsertAcceptsPublicGameIdentifier() {
        UUID installationId = UUID.randomUUID();
        UUID databaseGameId = UUID.randomUUID();
        RecordingAttendanceRepository attendanceRepository = new RecordingAttendanceRepository();
        attendanceRepository.resolvedGameId = databaseGameId;
        AttendanceService service = new AttendanceService(attendanceRepository);

        service.upsert(installationId.toString(), "20260720-LG-SSG");

        org.assertj.core.api.Assertions.assertThat(attendanceRepository.lastResolvedIdentifier)
                .isEqualTo("20260720-LG-SSG");
        org.assertj.core.api.Assertions.assertThat(attendanceRepository.upsertedGameId)
                .isEqualTo(databaseGameId);
    }

    @Test
    void listReturnsCompatibleIdsAndDetailedRecords() {
        UUID installationId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        RecordingAttendanceRepository attendanceRepository = new RecordingAttendanceRepository();
        attendanceRepository.records = List.of(new AttendanceGameRecordRow(
                gameId,
                "20260720-LG-SSG",
                LocalDate.of(2026, 7, 20),
                OffsetDateTime.parse("2026-07-20T18:30:00+09:00"),
                "잠실",
                "final",
                false,
                false,
                "lg",
                "LG 트윈스",
                "LG",
                null,
                "ssg",
                "SSG 랜더스",
                "SSG",
                null,
                4,
                2
        ));
        AttendanceService service = new AttendanceService(attendanceRepository);

        var response = service.list(installationId.toString());

        org.assertj.core.api.Assertions.assertThat(response.gameIds()).containsExactly(gameId);
        org.assertj.core.api.Assertions.assertThat(response.records()).singleElement().satisfies(record -> {
            org.assertj.core.api.Assertions.assertThat(record.publicGameId()).isEqualTo("20260720-LG-SSG");
            org.assertj.core.api.Assertions.assertThat(record.awayTeam().id()).isEqualTo("lg");
            org.assertj.core.api.Assertions.assertThat(record.homeScore()).isEqualTo(2);
        });
    }

    private static final class RecordingAttendanceRepository extends AttendanceRepository {
        private int upsertCount;
        private UUID resolvedGameId;
        private UUID upsertedGameId;
        private String lastResolvedIdentifier;
        private List<AttendanceGameRecordRow> records = List.of();
        private UUID deletedInstallationId;
        private UUID deletedGameId;

        private RecordingAttendanceRepository() {
            super(null);
        }

        @Override
        public Optional<UUID> resolveGameId(String gameIdentifier) {
            lastResolvedIdentifier = gameIdentifier;
            return Optional.ofNullable(resolvedGameId);
        }

        @Override
        public void upsert(UUID installationId, UUID gameId) {
            upsertCount++;
            upsertedGameId = gameId;
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

        @Override
        public List<AttendanceGameRecordRow> findRecords(UUID installationId) {
            return records;
        }
    }
}
