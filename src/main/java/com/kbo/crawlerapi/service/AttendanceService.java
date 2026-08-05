package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.InvalidParameterException;
import com.kbo.crawlerapi.api.ResourceNotFoundException;
import com.kbo.crawlerapi.api.dto.AttendanceGameDto;
import com.kbo.crawlerapi.api.dto.AttendanceListResponse;
import com.kbo.crawlerapi.api.dto.TeamSummaryDto;
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
        UUID normalizedGameId = requireExistingGame(gameId);
        attendanceRepository.upsert(normalizedInstallationId, normalizedGameId);
    }

    @Transactional
    public void delete(String installationId, String gameId) {
        UUID normalizedInstallationId = requireUuid(installationId, "installationId");
        UUID normalizedGameId = requireExistingGame(gameId);
        attendanceRepository.delete(normalizedInstallationId, normalizedGameId);
    }

    @Transactional(readOnly = true)
    public List<UUID> listGameIds(String installationId) {
        UUID normalizedInstallationId = requireUuid(installationId, "installationId");
        return attendanceRepository.findGameIds(normalizedInstallationId);
    }

    @Transactional(readOnly = true)
    public AttendanceListResponse list(String installationId) {
        UUID normalizedInstallationId = requireUuid(installationId, "installationId");
        var records = attendanceRepository.findRecords(normalizedInstallationId);
        return new AttendanceListResponse(
                records.stream().map(record -> record.gameId()).toList(),
                records.stream().map(record -> new AttendanceGameDto(
                        record.gameId(),
                        record.publicGameId(),
                        record.gameDate(),
                        record.scheduledAt(),
                        record.stadium(),
                        record.status(),
                        record.cancelled(),
                        record.postponed(),
                        new TeamSummaryDto(
                                record.awayTeamId(),
                                record.awayTeamName(),
                                record.awayTeamShortName(),
                                record.awayTeamLogoUrl()
                        ),
                        new TeamSummaryDto(
                                record.homeTeamId(),
                                record.homeTeamName(),
                                record.homeTeamShortName(),
                                record.homeTeamLogoUrl()
                        ),
                        record.awayScore(),
                        record.homeScore()
                )).toList()
        );
    }

    private UUID requireExistingGame(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw new InvalidParameterException("gameId is required");
        }
        String identifier = gameId.trim();
        if (identifier.length() > 100) {
            throw new InvalidParameterException("gameId is too long");
        }
        return attendanceRepository.resolveGameId(identifier)
                .orElseThrow(() -> new ResourceNotFoundException("game not found: " + identifier));
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
