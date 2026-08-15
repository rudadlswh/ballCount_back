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
@Tag(name = "관리자 알림", description = "관리자 전용 알림 테스트 API입니다.")
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
            summary = "종료 경기의 저장된 스냅샷으로 알림 이벤트 재생",
            description = "저장된 game_snapshots를 시간순으로 재생하고 지정한 installationId에만 테스트 알림을 전송합니다. 관리자 API 키가 필요합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "알림 재생 결과",
                    content = @Content(schema = @Schema(implementation = ReplayFinishedGameNotificationTestResult.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 알림 재생 요청",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "관리자 API 키가 없거나 올바르지 않음"
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
            @Schema(description = "games.id에 저장된 내부 UUID", example = "8e8f7cc5-3c62-45bb-9b50-6e75a82b5a11")
            UUID gameId,
            @Schema(description = "공개 경기 식별자", example = "20260617-SSG-LOT")
            String publicGameId,
            @Schema(description = "데이터 제공자의 경기 식별자", example = "20260617LTSK0")
            String providerGameId,
            @Schema(description = "필수 대상 installation_id입니다. notification_devices에서 값이 일치하는 행만 사용합니다.", requiredMode = Schema.RequiredMode.REQUIRED, example = "test-installation-id")
            String installationId,
            @Schema(description = "대상 APNs 환경입니다. 기본값은 production입니다.", allowableValues = {"sandbox", "production"}, defaultValue = "production")
            String environment,
            @Schema(
                    description = "재생할 이벤트 유형입니다. 비어 있거나 생략하면 재생 가능한 모든 이벤트를 사용합니다.",
                    allowableValues = {"GAME_START", "INNING_CHANGED", "SCORE_CHANGED", "LEAD_CHANGED", "ON_BASE", "GAME_END"}
            )
            List<String> eventTypes,
            @Schema(description = "생성할 최대 이벤트 수입니다. 기본값은 20이며 최대 100개입니다.", defaultValue = "20", maximum = "100")
            Integer maxEvents,
            @Schema(description = "true이면 APNs로 전송하지 않고 생성된 알림만 반환합니다.", defaultValue = "false")
            Boolean dryRun
    ) {
    }
}
