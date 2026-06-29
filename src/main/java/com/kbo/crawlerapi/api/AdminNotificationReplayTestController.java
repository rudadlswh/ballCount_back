package com.kbo.crawlerapi.api;

import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService.ReplayFinishedGameNotificationTestCommand;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService.ReplayFinishedGameNotificationTestResult;
import com.kbo.crawlerapi.service.LiveGameSyncService;
import com.kbo.crawlerapi.service.LiveGameSyncService.NotificationRecoveryDiagnosis;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin Notifications", description = "Administrator-only notification test APIs.")
public class AdminNotificationReplayTestController {

    private final FinishedGameNotificationReplayTestService replayTestService;
    private final LiveGameSyncService liveGameSyncService;

    public AdminNotificationReplayTestController(
            FinishedGameNotificationReplayTestService replayTestService,
            LiveGameSyncService liveGameSyncService
    ) {
        this.replayTestService = replayTestService;
        this.liveGameSyncService = liveGameSyncService;
    }

    @Operation(
            summary = "Replay notification events from a finished game's stored snapshots",
            description = "Replays stored game_snapshots in chronological order and sends test notifications only to the requested installationId. Requires the admin API key."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Replay result",
                    content = @Content(schema = @Schema(implementation = ReplayFinishedGameNotificationTestResult.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid replay request",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Missing or invalid admin API key"
            )
    })
    @PostMapping("/admin/test/notifications/replay-finished-game")
    public ReplayFinishedGameNotificationTestResult replayFinishedGame(@RequestBody ReplayFinishedGameNotificationTestRequest request) {
        return replayTestService.replay(new ReplayFinishedGameNotificationTestCommand(
                request.gameId(),
                request.publicGameId(),
                request.providerGameId(),
                request.installationId(),
                request.environment(),
                request.eventTypes(),
                request.maxEvents(),
                Boolean.TRUE.equals(request.dryRun())
        ));
    }

    @GetMapping("/admin/test/notifications/diagnose-game")
    public NotificationRecoveryDiagnosis diagnoseGame(@RequestParam String publicGameId) {
        return liveGameSyncService.diagnoseNotificationRecovery(publicGameId);
    }

    @Schema(name = "ReplayFinishedGameNotificationTestRequest")
    public record ReplayFinishedGameNotificationTestRequest(
            @Schema(description = "Internal UUID from games.id", example = "8e8f7cc5-3c62-45bb-9b50-6e75a82b5a11")
            UUID gameId,
            @Schema(description = "Public game identifier", example = "20260617-SSG-LOT")
            String publicGameId,
            @Schema(description = "Provider game identifier", example = "20260617LTSK0")
            String providerGameId,
            @Schema(description = "Required target installation_id. Only matching notification_devices rows are considered.", requiredMode = Schema.RequiredMode.REQUIRED, example = "test-installation-id")
            String installationId,
            @Schema(description = "Target APNs environment. Defaults to production.", allowableValues = {"sandbox", "production"}, defaultValue = "production")
            String environment,
            @Schema(
                    description = "Event types to replay. Empty or omitted means all replayable event types.",
                    allowableValues = {"GAME_START", "INNING_CHANGED", "SCORE_CHANGED", "LEAD_CHANGED", "ON_BASE", "GAME_END"}
            )
            List<String> eventTypes,
            @Schema(description = "Maximum generated events. Defaults to 20 and is capped at 100.", defaultValue = "20", maximum = "100")
            Integer maxEvents,
            @Schema(description = "When true, returns generated notifications without sending APNs.", defaultValue = "false")
            Boolean dryRun
    ) {
    }
}
