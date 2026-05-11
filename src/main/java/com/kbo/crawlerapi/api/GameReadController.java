package com.kbo.crawlerapi.api;

import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kbo.crawlerapi.api.dto.GameBoxscoreResponse;
import com.kbo.crawlerapi.api.dto.GameDetailResponse;
import com.kbo.crawlerapi.api.dto.GameLineScoreResponse;
import com.kbo.crawlerapi.api.dto.GamesByDateResponse;
import com.kbo.crawlerapi.api.dto.GamesByMonthResponse;
import com.kbo.crawlerapi.api.dto.ScoreboardResponse;
import com.kbo.crawlerapi.service.GameReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Games", description = "App-facing read APIs for normalized KBO schedule, detail, and scoreboard data.")
public class GameReadController {

    private final GameReadService gameReadService;

    public GameReadController(GameReadService gameReadService) {
        this.gameReadService = gameReadService;
    }

    @GetMapping("/games")
    @Operation(
            summary = "List games for one date",
            description = "Returns normalized schedule/detail summary rows for the requested KBO game date. "
                    + "No-game days return 200 with an empty games array."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Games for the requested date",
                    content = @Content(schema = @Schema(implementation = GamesByDateResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid date parameter",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GamesByDateResponse getGamesByDate(
            @Parameter(description = "KBO game date in YYYY-MM-DD format.", example = "2026-04-09")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return gameReadService.getGamesByDate(date);
    }

    @GetMapping("/games/{gameId}")
    @Operation(
            summary = "Get one game detail",
            description = "Returns normalized detail for one game using the public game ID. "
                    + "Scheduled games keep live-only fields nullable until source data exists."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Normalized game detail",
                    content = @Content(schema = @Schema(implementation = GameDetailResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Game not found",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GameDetailResponse getGameDetail(
            @Parameter(description = "Public game identifier exposed by the app-facing API.", example = "20260401-LG-KIA")
            @PathVariable String gameId
    ) {
        return gameReadService.getGameDetail(gameId);
    }

    @GetMapping("/games/{gameId}/linescore")
    @Operation(
            summary = "Get one game line score",
            description = "Returns numbered inning lines and totals for one game. "
                    + "Scheduled games without line score data return 200 with an empty innings array and null totals."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Normalized inning-by-inning line score",
                    content = @Content(schema = @Schema(implementation = GameLineScoreResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Game not found",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GameLineScoreResponse getGameLineScore(
            @Parameter(description = "Public game identifier exposed by the app-facing API.", example = "20260401-LG-KIA")
            @PathVariable String gameId
    ) {
        return gameReadService.getGameLineScore(gameId);
    }

    @GetMapping("/games/{gameId}/boxscore")
    @Operation(
            summary = "Get one game boxscore",
            description = "Returns normalized batter and pitcher boxscore records for one game. "
                    + "Games without persisted boxscore rows return 200 with empty record arrays."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Normalized batter and pitcher boxscore records",
                    content = @Content(schema = @Schema(implementation = GameBoxscoreResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Game not found",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GameBoxscoreResponse getGameBoxscore(
            @Parameter(description = "Public game identifier exposed by the app-facing API.", example = "20260401-LG-KIA")
            @PathVariable String gameId
    ) {
        return gameReadService.getGameBoxscore(gameId);
    }

    @GetMapping("/games/month")
    @Operation(
            summary = "List games for one month",
            description = "Returns normalized schedule/detail summary rows for the requested year and month. "
                    + "A valid month with no imported games returns 200 with an empty games array."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Games for the requested month",
                    content = @Content(schema = @Schema(implementation = GamesByMonthResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid year or month parameter",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GamesByMonthResponse getGamesByMonth(
            @Parameter(description = "Four-digit KBO season year.", example = "2026")
            @RequestParam int year,
            @Parameter(description = "Calendar month in the range 1-12.", example = "4")
            @RequestParam int month
    ) {
        try {
            return gameReadService.getGamesByMonth(YearMonth.of(year, month));
        } catch (java.time.DateTimeException exception) {
            throw new InvalidParameterException("month must be in the range 1-12");
        }
    }

    @GetMapping("/scoreboard")
    @Operation(
            summary = "Get scoreboard view for one date",
            description = "Returns the compact scoreboard view for one date. "
                    + "When the date parameter is omitted, the service uses the application clock's current KST date."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Scoreboard rows for the requested or default date",
                    content = @Content(schema = @Schema(implementation = ScoreboardResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid date parameter",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ScoreboardResponse getScoreboard(
            @Parameter(
                    description = "KBO game date in YYYY-MM-DD format. When omitted, the current KST date is used.",
                    examples = {
                            @ExampleObject(name = "today", value = "2026-04-09")
                    }
            )
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return gameReadService.getScoreboard(date);
    }
}
