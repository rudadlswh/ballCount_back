package com.kbo.crawlerapi.api;

import com.kbo.crawlerapi.api.dto.StandingsResponse;
import com.kbo.crawlerapi.api.dto.TeamsResponse;
import com.kbo.crawlerapi.service.LeagueReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LeagueReadController {

    private final LeagueReadService leagueReadService;

    public LeagueReadController(LeagueReadService leagueReadService) {
        this.leagueReadService = leagueReadService;
    }

    @GetMapping("/teams")
    public TeamsResponse getTeams() {
        return leagueReadService.getTeams();
    }

    @GetMapping("/standings")
    public StandingsResponse getStandings(@RequestParam int season) {
        try {
            return leagueReadService.getStandings(season);
        } catch (IllegalArgumentException exception) {
            throw new InvalidParameterException(exception.getMessage());
        }
    }
}
