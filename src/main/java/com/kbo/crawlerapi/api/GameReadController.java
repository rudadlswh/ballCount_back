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
import com.kbo.crawlerapi.api.dto.GameDetailDataResponse;
import com.kbo.crawlerapi.api.dto.GameLineScoreResponse;
import com.kbo.crawlerapi.api.dto.GameLineupResponse;
import com.kbo.crawlerapi.api.dto.GameLiveStateResponse;
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
@Tag(name = "경기", description = "정규화된 KBO 일정, 경기 상세, 스코어보드 데이터를 조회하는 앱용 API입니다.")
public class GameReadController {

    private static final int MAX_PUBLIC_GAME_ID_LENGTH = 100;

    private final GameReadService gameReadService;

    public GameReadController(GameReadService gameReadService) {
        this.gameReadService = gameReadService;
    }

    @GetMapping("/games")
    @Operation(
            summary = "날짜별 경기 목록 조회",
            description = "요청한 KBO 경기일의 정규화된 일정 및 상세 요약을 반환합니다. "
                    + "경기가 없는 날은 빈 games 배열과 함께 200 응답을 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "요청한 날짜의 경기 목록",
                    content = @Content(schema = @Schema(implementation = GamesByDateResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 날짜 파라미터",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GamesByDateResponse getGamesByDate(
            @Parameter(description = "YYYY-MM-DD 형식의 KBO 경기일", example = "2026-04-09")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return gameReadService.getGamesByDate(date);
    }

    @GetMapping("/games/{gameId}")
    @Operation(
            summary = "경기 상세 조회",
            description = "공개 경기 ID로 한 경기의 정규화된 상세 정보를 반환합니다. "
                    + "예정된 경기는 원본 데이터가 생길 때까지 실시간 전용 필드가 null일 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "정규화된 경기 상세 정보",
                    content = @Content(schema = @Schema(implementation = GameDetailResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "경기를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GameDetailResponse getGameDetail(
            @Parameter(description = "앱용 API에서 사용하는 공개 경기 식별자", example = "20260401-LG-KIA")
            @PathVariable String gameId
    ) {
        validateGameId(gameId);
        return gameReadService.getGameDetail(gameId);
    }

    @GetMapping("/games/{gameId}/detail")
    @Operation(
            summary = "통합 경기 상세 데이터 조회",
            description = "경기 요약, 실시간 상태, 이닝별 점수, 박스스코어, 라인업을 포함한 보정된 단일 스냅샷을 반환합니다. "
                    + "선택 정보가 없으면 해당 영역을 빈 값으로 정상 반환하며, appliedFallbacks에는 서버에서 적용한 보정 내용이 표시됩니다. "
                    + "기존 개별 정보 API도 계속 사용할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "보정된 통합 경기 상세 데이터",
                    content = @Content(schema = @Schema(implementation = GameDetailDataResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "경기를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GameDetailDataResponse getGameDetailData(
            @Parameter(description = "앱용 API에서 사용하는 공개 경기 식별자", example = "20260401-LG-KIA")
            @PathVariable String gameId
    ) {
        validateGameId(gameId);
        return gameReadService.getGameDetailData(gameId);
    }

    @GetMapping({"/games/{publicGameId}/live-state", "/games/{publicGameId}/realtime"})
    @Operation(
            summary = "경기 실시간 상태 조회",
            description = "경기 상세 화면을 빠르게 폴링하는 데 필요한 간결한 실시간 상태를 반환합니다. "
                    + "클라이언트는 rawHash를 비교해 변경이 없을 때 UI 갱신을 생략할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "간결한 최신 경기 상태",
                    content = @Content(schema = @Schema(implementation = GameLiveStateResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "경기를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GameLiveStateResponse getGameLiveState(
            @Parameter(description = "앱용 API에서 사용하는 공개 경기 식별자", example = "20260401-LG-KIA")
            @PathVariable("publicGameId") String gameId
    ) {
        validateGameId(gameId);
        return gameReadService.getGameLiveState(gameId);
    }

    @GetMapping("/games/{gameId}/linescore")
    @Operation(
            summary = "경기 이닝별 점수 조회",
            description = "한 경기의 이닝별 점수와 합계를 반환합니다. "
                    + "이닝별 점수 데이터가 없는 예정 경기는 빈 innings 배열과 null 합계로 200 응답을 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "정규화된 이닝별 점수",
                    content = @Content(schema = @Schema(implementation = GameLineScoreResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "경기를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GameLineScoreResponse getGameLineScore(
            @Parameter(description = "앱용 API에서 사용하는 공개 경기 식별자", example = "20260401-LG-KIA")
            @PathVariable String gameId
    ) {
        validateGameId(gameId);
        return gameReadService.getGameLineScore(gameId);
    }

    @GetMapping("/games/{gameId}/boxscore")
    @Operation(
            summary = "경기 박스스코어 조회",
            description = "한 경기의 정규화된 타자 및 투수 박스스코어 기록을 반환합니다. "
                    + "저장된 박스스코어가 없는 경기는 빈 기록 배열과 함께 200 응답을 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "정규화된 타자 및 투수 박스스코어 기록",
                    content = @Content(schema = @Schema(implementation = GameBoxscoreResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "경기를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GameBoxscoreResponse getGameBoxscore(
            @Parameter(description = "앱용 API에서 사용하는 공개 경기 식별자", example = "20260401-LG-KIA")
            @PathVariable String gameId
    ) {
        validateGameId(gameId);
        return gameReadService.getGameBoxscore(gameId);
    }

    @GetMapping("/games/{gameId}/lineup")
    public GameLineupResponse getGameLineup(@PathVariable String gameId) {
        validateGameId(gameId);
        return gameReadService.getGameLineup(gameId);
    }

    @GetMapping("/games/month")
    @Operation(
            summary = "월별 경기 목록 조회",
            description = "요청한 연도와 월의 정규화된 일정 및 상세 요약을 반환합니다. "
                    + "유효한 월에 수집된 경기가 없으면 빈 games 배열과 함께 200 응답을 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "요청한 월의 경기 목록",
                    content = @Content(schema = @Schema(implementation = GamesByMonthResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 연도 또는 월 파라미터",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public GamesByMonthResponse getGamesByMonth(
            @Parameter(description = "네 자리 KBO 시즌 연도", example = "2026")
            @RequestParam int year,
            @Parameter(description = "1~12 범위의 월", example = "4")
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
            summary = "날짜별 스코어보드 조회",
            description = "한 날짜의 간결한 스코어보드를 반환합니다. "
                    + "date 파라미터를 생략하면 애플리케이션 시계의 현재 한국 표준시 날짜를 사용합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "요청한 날짜 또는 기본 날짜의 스코어보드",
                    content = @Content(schema = @Schema(implementation = ScoreboardResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 날짜 파라미터",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ScoreboardResponse getScoreboard(
            @Parameter(
                    description = "YYYY-MM-DD 형식의 KBO 경기일입니다. 생략하면 현재 한국 표준시 날짜를 사용합니다.",
                    examples = {
                            @ExampleObject(name = "오늘", value = "2026-04-09")
                    }
            )
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return gameReadService.getScoreboard(date);
    }

    private void validateGameId(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw new InvalidParameterException("gameId is required");
        }
        if (gameId.length() > MAX_PUBLIC_GAME_ID_LENGTH) {
            throw new InvalidParameterException("gameId is too long");
        }
    }
}
