package com.kbo.crawlerapi.api;

import com.kbo.crawlerapi.service.TeamRankService;
import com.kbo.crawlerapi.service.TeamRankService.TeamRankRefreshResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/ranks")
public class AdminRankController {

    private final TeamRankService teamRankService;

    public AdminRankController(TeamRankService teamRankService) {
        this.teamRankService = teamRankService;
    }

    @PostMapping("/refresh")
    public TeamRankRefreshResult refresh(@RequestParam int season) {
        return teamRankService.refreshSeasonRankings(season);
    }
}
