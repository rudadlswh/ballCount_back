package com.kbo.crawlerapi.api;

import com.kbo.crawlerapi.service.StaleGameReconciliationService;
import com.kbo.crawlerapi.service.StaleGameReconciliationService.StaleGameReconciliationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "경기", description = "정규화된 KBO 경기 데이터를 조회하고 갱신하는 앱용 API입니다.")
public class StaleGameReconciliationController {

    private final StaleGameReconciliationService staleGameReconciliationService;

    public StaleGameReconciliationController(StaleGameReconciliationService staleGameReconciliationService) {
        this.staleGameReconciliationService = staleGameReconciliationService;
    }

    @PostMapping({"/api/v1/games/reconcile-stale", "/games/reconcile-stale"})
    @Operation(
            summary = "갱신되지 않은 과거 경기 보정",
            description = "앱에서 지정한 날짜의 일정 데이터를 갱신하고, 대상 경기에 대해 상세 정보 수집을 실행합니다."
    )
    public StaleGameReconciliationResult reconcileStaleGames(
            @RequestBody StaleGameReconciliationRequest request
    ) {
        if (request == null || request.dates() == null || request.dates().isEmpty()) {
            throw new InvalidParameterException("dates must not be empty");
        }
        return staleGameReconciliationService.reconcilePublic(request.dates());
    }

    @PostMapping("/admin/games/reconcile-stale")
    public StaleGameReconciliationResult reconcileStaleGamesAsAdmin(
            @RequestBody StaleGameReconciliationRequest request
    ) {
        if (request == null || request.dates() == null || request.dates().isEmpty()) {
            throw new InvalidParameterException("dates must not be empty");
        }
        return staleGameReconciliationService.reconcileAdmin(request.dates());
    }

    public record StaleGameReconciliationRequest(
            List<LocalDate> dates
    ) {
    }
}
