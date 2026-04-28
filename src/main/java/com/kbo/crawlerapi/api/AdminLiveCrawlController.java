package com.kbo.crawlerapi.api;

import com.kbo.crawlerapi.service.LiveGameSyncService;
import com.kbo.crawlerapi.service.LiveGameSyncService.LiveSyncSummary;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/crawl")
public class AdminLiveCrawlController {

    private final LiveGameSyncService liveGameSyncService;

    public AdminLiveCrawlController(LiveGameSyncService liveGameSyncService) {
        this.liveGameSyncService = liveGameSyncService;
    }

    @PostMapping("/live")
    public LiveSyncSummary crawlLive(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return liveGameSyncService.sync(date == null ? liveGameSyncService.todayKst() : date, true);
    }
}
