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
@Tag(name = "Games", description = "App-facing read and refresh APIs for normalized KBO game data.")
public class StaleGameReconciliationController {

    private final StaleGameReconciliationService staleGameReconciliationService;

    public StaleGameReconciliationController(StaleGameReconciliationService staleGameReconciliationService) {
        this.staleGameReconciliationService = staleGameReconciliationService;
    }

    @PostMapping({"/api/v1/games/reconcile-stale", "/games/reconcile-stale"})
    @Operation(
            summary = "Reconcile stale past schedule games",
            description = "Refreshes schedule data and runs eligible detail imports for explicit dates selected by the app."
    )
    public StaleGameReconciliationResult reconcileStaleGames(
            @RequestBody StaleGameReconciliationRequest request
    ) {
        if (request == null || request.dates() == null || request.dates().isEmpty()) {
            throw new InvalidParameterException("dates must not be empty");
        }
        return staleGameReconciliationService.reconcile(request.dates());
    }

    public record StaleGameReconciliationRequest(
            List<LocalDate> dates
    ) {
    }
}
