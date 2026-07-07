package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.InvalidParameterException;
import com.kbo.crawlerapi.api.ResourceNotFoundException;
import com.kbo.crawlerapi.repository.AttendanceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;

    public AttendanceService(AttendanceRepository attendanceRepository) {
        this.attendanceRepository = attendanceRepository;
    }

    @Transactional
    public void upsert(String installationId, String gameId) {
        UUID normalizedInstallationId = requireUuid(installationId, "installationId");
        UUID normalizedGameId = requireUuid(gameId, "gameId");
        requireExistingGame(normalizedGameId);
        attendanceRepository.upsert(normalizedInstallationId, normalizedGameId);
    }

    @Transactional
    public void delete(String installationId, String gameId) {
        UUID normalizedInstallationId = requireUuid(installationId, "installationId");
        UUID normalizedGameId = requireUuid(gameId, "gameId");
        requireExistingGame(normalizedGameId);
        attendanceRepository.delete(normalizedInstallationId, normalizedGameId);
    }

    @Transactional(readOnly = true)
    public List<UUID> listGameIds(String installationId) {
        UUID normalizedInstallationId = requireUuid(installationId, "installationId");
        return attendanceRepository.findGameIds(normalizedInstallationId);
    }

    private void requireExistingGame(UUID gameId) {
        if (!attendanceRepository.gameExists(gameId)) {
            throw new ResourceNotFoundException("game not found: " + gameId);
        }
    }

    private UUID requireUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidParameterException(fieldName + " is required");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new InvalidParameterException(fieldName + " must be a UUID");
        }
    }
}
