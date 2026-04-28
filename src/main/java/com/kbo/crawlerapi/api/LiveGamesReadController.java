package com.kbo.crawlerapi.api;

import com.kbo.crawlerapi.api.dto.ScoreboardResponse;
import com.kbo.crawlerapi.service.GameReadService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LiveGamesReadController {

    private final GameReadService gameReadService;

    public LiveGamesReadController(GameReadService gameReadService) {
        this.gameReadService = gameReadService;
    }

    @GetMapping({"/games/live", "/api/v1/games/live"})
    public ScoreboardResponse getLiveGames(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return gameReadService.getScoreboard(date);
    }
}
