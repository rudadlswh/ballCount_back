package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.InvalidParameterException;
import com.kbo.crawlerapi.api.ResourceNotFoundException;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import com.kbo.crawlerapi.service.NotificationEventService.NotificationEventDraft;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinishedGameNotificationReplayTestService {

    public static final int DEFAULT_MAX_EVENTS = 20;
    public static final int MAX_EVENTS_LIMIT = 100;
    private static final Set<String> REPLAYABLE_EVENT_TYPES = Set.of(
            NotificationEventService.EVENT_GAME_START,
            NotificationEventService.EVENT_INNING_CHANGED,
            NotificationEventService.EVENT_SCORE_CHANGED,
            NotificationEventService.EVENT_LEAD_CHANGED,
            NotificationEventService.EVENT_ON_BASE,
            NotificationEventService.EVENT_GAME_END
    );

    private final GameRepository gameRepository;
    private final GameSnapshotRepository gameSnapshotRepository;
    private final NotificationDeviceRepository notificationDeviceRepository;
    private final LiveGameSyncService liveGameSyncService;
    private final NotificationEventService notificationEventService;
    private final ApnsPushService apnsPushService;

    public FinishedGameNotificationReplayTestService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            NotificationDeviceRepository notificationDeviceRepository,
            LiveGameSyncService liveGameSyncService,
            NotificationEventService notificationEventService,
            ApnsPushService apnsPushService
    ) {
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.notificationDeviceRepository = notificationDeviceRepository;
        this.liveGameSyncService = liveGameSyncService;
        this.notificationEventService = notificationEventService;
        this.apnsPushService = apnsPushService;
    }

    @Transactional(readOnly = true)
    public ReplayFinishedGameNotificationTestResult replay(ReplayFinishedGameNotificationTestCommand command) {
        String installationId = requireText(command.installationId(), "installationId");
        int maxEvents = boundedMaxEvents(command.maxEvents());
        Set<String> eventTypes = normalizeEventTypes(command.eventTypes());
        Game game = findGame(command);
        validateFinishedGame(game);

        List<GameSnapshot> snapshots = gameSnapshotRepository.findReplaySnapshotsByGameId(game.getId());
        if (snapshots.size() < 2) {
            throw new InvalidParameterException("at least 2 game snapshots are required");
        }

        List<NotificationEventDraft> drafts = liveGameSyncService.replayNotificationDrafts(
                game,
                snapshots,
                eventTypes,
                maxEvents,
                installationId
        );
        List<NotificationDevice> targetDevices = notificationDeviceRepository.findByInstallationId(installationId);

        int attemptedCount = 0;
        int sentCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        List<ReplayFinishedGameNotificationEventResult> events = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            NotificationEventDraft draft = drafts.get(index);
            DeliveryOutcome outcome = command.dryRun()
                    ? DeliveryOutcome.dryRun()
                    : deliver(game, draft, targetDevices);
            attemptedCount += outcome.attemptedCount();
            sentCount += outcome.sentCount();
            skippedCount += outcome.skippedCount();
            failedCount += outcome.failedCount();
            events.add(new ReplayFinishedGameNotificationEventResult(
                    index + 1,
                    draft.eventType(),
                    draft.title(),
                    draft.body(),
                    outcome.deliveryStatus(),
                    outcome.reason()
            ));
        }

        return new ReplayFinishedGameNotificationTestResult(
                game.getId().toString(),
                game.getPublicGameId(),
                installationId,
                command.dryRun(),
                drafts.size(),
                attemptedCount,
                sentCount,
                skippedCount,
                failedCount,
                events
        );
    }

    private DeliveryOutcome deliver(Game game, NotificationEventDraft draft, List<NotificationDevice> targetDevices) {
        if (targetDevices.isEmpty()) {
            return DeliveryOutcome.skipped("no_matching_installation");
        }

        int attempted = 0;
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        String lastReason = null;
        NotificationEvent event = notificationEventService.transientEvent(game, draft);
        for (NotificationDevice device : targetDevices) {
            String deviceSkipReason = notificationEventService.targetedDeviceSkipReason(device, draft, game);
            if (deviceSkipReason != null) {
                skipped++;
                lastReason = deviceSkipReason;
                continue;
            }
            attempted++;
            ApnsPushService.ApnsSendResult result = apnsPushService.send(event, device);
            if (result.sent()) {
                sent++;
            } else if (result.skipped()) {
                skipped++;
                lastReason = result.reason();
            } else {
                failed++;
                lastReason = result.reason();
            }
        }
        String status = sent > 0 && failed == 0
                ? "sent"
                : sent > 0
                ? "partial_failed"
                : failed > 0
                ? "failed"
                : "skipped";
        return new DeliveryOutcome(status, lastReason, attempted, sent, skipped, failed);
    }

    private Game findGame(ReplayFinishedGameNotificationTestCommand command) {
        if (command.gameId() != null) {
            return gameRepository.findById(command.gameId())
                    .orElseThrow(() -> new ResourceNotFoundException("game not found: " + command.gameId()));
        }
        if (hasText(command.publicGameId())) {
            return gameRepository.findByPublicGameId(command.publicGameId().trim())
                    .orElseThrow(() -> new ResourceNotFoundException("game not found: " + command.publicGameId()));
        }
        if (hasText(command.providerGameId())) {
            return gameRepository.findByProviderGameId(command.providerGameId().trim())
                    .orElseThrow(() -> new ResourceNotFoundException("game not found: " + command.providerGameId()));
        }
        throw new InvalidParameterException("one of gameId, publicGameId, providerGameId is required");
    }

    private void validateFinishedGame(Game game) {
        if (game.getStatus() != GameStatus.FINAL) {
            throw new InvalidParameterException("only finished games can be replayed");
        }
    }

    private Set<String> normalizeEventTypes(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return REPLAYABLE_EVENT_TYPES;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String eventType : eventTypes) {
            if (eventType == null || eventType.isBlank()) {
                continue;
            }
            String value = eventType.trim().toUpperCase(Locale.ROOT);
            if (!REPLAYABLE_EVENT_TYPES.contains(value)) {
                throw new InvalidParameterException("unsupported eventType: " + eventType);
            }
            normalized.add(value);
        }
        return normalized.isEmpty() ? REPLAYABLE_EVENT_TYPES : normalized;
    }

    private int boundedMaxEvents(Integer maxEvents) {
        if (maxEvents == null) {
            return DEFAULT_MAX_EVENTS;
        }
        if (maxEvents <= 0) {
            throw new InvalidParameterException("maxEvents must be positive");
        }
        return Math.min(maxEvents, MAX_EVENTS_LIMIT);
    }

    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new InvalidParameterException(fieldName + " is required");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ReplayFinishedGameNotificationTestCommand(
            UUID gameId,
            String publicGameId,
            String providerGameId,
            String installationId,
            List<String> eventTypes,
            Integer maxEvents,
            boolean dryRun
    ) {
    }

    public record ReplayFinishedGameNotificationTestResult(
            String gameId,
            String publicGameId,
            String targetInstallationId,
            boolean dryRun,
            int generatedCount,
            int attemptedCount,
            int sentCount,
            int skippedCount,
            int failedCount,
            List<ReplayFinishedGameNotificationEventResult> events
    ) {
    }

    public record ReplayFinishedGameNotificationEventResult(
            int index,
            String eventType,
            String title,
            String body,
            String deliveryStatus,
            String reason
    ) {
    }

    private record DeliveryOutcome(
            String deliveryStatus,
            String reason,
            int attemptedCount,
            int sentCount,
            int skippedCount,
            int failedCount
    ) {
        private static DeliveryOutcome dryRun() {
            return new DeliveryOutcome("dry_run", null, 0, 0, 0, 0);
        }

        private static DeliveryOutcome skipped(String reason) {
            return new DeliveryOutcome("skipped", reason, 0, 0, 1, 0);
        }
    }
}
