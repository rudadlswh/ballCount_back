package com.kbo.crawlerapi.api;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService.DetailRefreshPassResult;
import io.swagger.v3.oas.annotations.Hidden;

@RestController
@RequestMapping("/internal/orchestration")
@Hidden
public class InternalDetailRefreshOrchestrationController {

    private final DetailRefreshOrchestratorService detailRefreshOrchestratorService;

    public InternalDetailRefreshOrchestrationController(DetailRefreshOrchestratorService detailRefreshOrchestratorService) {
        this.detailRefreshOrchestratorService = detailRefreshOrchestratorService;
    }

    @PostMapping("/detail-refresh-pass")
    public DetailRefreshPassResult runDetailRefreshPass(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean execute
    ) {
        return detailRefreshOrchestratorService.runPass(date, execute);
    }
}
