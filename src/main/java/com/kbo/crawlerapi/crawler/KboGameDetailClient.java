package com.kbo.crawlerapi.crawler;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class KboGameDetailClient {

    private static final Logger log = LoggerFactory.getLogger(KboGameDetailClient.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String BASE_URL = "https://www.koreabaseball.com";
    private static final String GAME_LIST_ENDPOINT_PATH = "/ws/Main.asmx/GetKboGameList";
    private static final String SCOREBOARD_ENDPOINT_PATH = "/ws/Schedule.asmx/GetScoreBoardScroll";
    private static final String SCOREBOARD_PAGE_PATH = "/Schedule/ScoreBoard.aspx";
    private static final String BOXSCORE_ENDPOINT_PATH = "/ws/Schedule.asmx/GetBoxScoreScroll";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private final RestClient restClient;
    private final String baseUrl;

    public KboGameDetailClient() {
        this(RestClient.builder(), BASE_URL);
    }

    protected KboGameDetailClient(RestClient.Builder restClientBuilder, String baseUrl) {
        this(restClientBuilder, baseUrl, true);
    }

    protected KboGameDetailClient(RestClient.Builder restClientBuilder, String baseUrl, boolean configureRequestFactory) {
        RestClient.Builder builder = restClientBuilder.baseUrl(baseUrl);
        if (configureRequestFactory) {
            builder.requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()));
        }
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
    }

    public String fetchGameList(LocalDate gameDate) {
        return fetchGameListResponse(gameDate).body();
    }

    public DetailResponse fetchGameListResponse(LocalDate gameDate) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("leId", "1");
        form.add("srId", "0,1,3,4,5,6,7,8,9");
        form.add("date", gameDate.format(DATE_FORMATTER));

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(GAME_LIST_ENDPOINT_PATH)
                    .contentType(MediaType.parseMediaType("application/x-www-form-urlencoded; charset=UTF-8"))
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .header(HttpHeaders.ACCEPT, "application/json, text/javascript, */*; q=0.01")
                    .header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header(HttpHeaders.REFERER, BASE_URL + "/Schedule/GameCenter/Main.aspx")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .body(form)
                    .retrieve()
                    .toEntity(String.class);
            DetailResponse detailResponse = toDetailResponse(response, GAME_LIST_ENDPOINT_PATH, "POST");
            log.debug(
                    "[KboGameDetailClient] response endpoint=gameList method={} uri={} status={} contentType={} responseType={} bodyLength={}",
                    detailResponse.method(),
                    detailResponse.requestUri(),
                    detailResponse.statusCode(),
                    detailResponse.contentType(),
                    detailResponse.responseType(),
                    detailResponse.bodyLength()
            );
            validateDetailResponse(detailResponse, "KBO game detail list endpoint returned error page");
            return detailResponse;
        } catch (RestClientException exception) {
            throw new IllegalStateException("Failed to fetch KBO game list for " + gameDate, exception);
        }
    }

    public String fetchScoreBoard(String providerGameId, int seasonId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("leId", "1");
        form.add("srId", "0");
        form.add("seasonId", String.valueOf(seasonId));
        form.add("gameId", providerGameId);

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(SCOREBOARD_ENDPOINT_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .header(HttpHeaders.ACCEPT, "application/json, text/javascript, */*; q=0.01")
                    .header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header(HttpHeaders.REFERER, BASE_URL + "/Schedule/GameCenter/Main.aspx?gameId=" + providerGameId)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .body(form)
                    .retrieve()
                    .toEntity(String.class);
            DetailResponse detailResponse = toDetailResponse(response, SCOREBOARD_ENDPOINT_PATH, "POST");
            logScoreBoardResponse("scoreBoard", detailResponse);
            validateDetailResponse(detailResponse, "KBO score board endpoint returned error page");
            return detailResponse.body();
        } catch (RestClientException exception) {
            throw new IllegalStateException("Failed to fetch KBO line score for " + providerGameId, exception);
        }
    }

    public String fetchScoreBoardPage(String providerGameId, LocalDate gameDate) {
        try {
            ResponseEntity<String> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(SCOREBOARD_PAGE_PATH)
                            .queryParam("gameDate", gameDate.format(DATE_FORMATTER))
                            .queryParam("gameId", providerGameId)
                            .build())
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header(HttpHeaders.REFERER, BASE_URL + "/Schedule/GameCenter/Main.aspx?gameId=" + providerGameId)
                    .retrieve()
                    .toEntity(String.class);
            DetailResponse detailResponse = toDetailResponse(response, SCOREBOARD_PAGE_PATH, "GET");
            logScoreBoardResponse("scoreBoardPage", detailResponse);
            validateDetailResponse(detailResponse, "KBO score board page returned error page");
            return detailResponse.body();
        } catch (RestClientException exception) {
            throw new IllegalStateException("Failed to fetch KBO score board page for " + providerGameId, exception);
        }
    }

    public String fetchBoxScore(String providerGameId, int seasonId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("leId", "1");
        form.add("srId", "0");
        form.add("seasonId", String.valueOf(seasonId));
        form.add("gameId", providerGameId);

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(BOXSCORE_ENDPOINT_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .header(HttpHeaders.ACCEPT, "application/json, text/javascript, */*; q=0.01")
                    .header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header(HttpHeaders.REFERER, BASE_URL + "/Schedule/GameCenter/Main.aspx?gameId=" + providerGameId)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .body(form)
                    .retrieve()
                    .toEntity(String.class);
            DetailResponse detailResponse = toDetailResponse(response, BOXSCORE_ENDPOINT_PATH, "POST");
            validateDetailResponse(detailResponse, "KBO box score endpoint returned error page");
            return detailResponse.body();
        } catch (RestClientException exception) {
            throw new IllegalStateException("Failed to fetch KBO box score for " + providerGameId, exception);
        }
    }

    private DetailResponse toDetailResponse(ResponseEntity<String> response, String endpointPath, String method) {
        return new DetailResponse(
                response.getBody(),
                response.getStatusCode().value(),
                response.getHeaders().getContentType() == null ? null : response.getHeaders().getContentType().toString(),
                URI.create(baseUrl + endpointPath).toString(),
                method
        );
    }

    private void logScoreBoardResponse(String source, DetailResponse detailResponse) {
        log.info(
                "[ScoreBoard] response source={} method={} uri={} status={} contentType={} responseType={} bodyLength={} bodyPreview={}",
                source,
                detailResponse.method(),
                detailResponse.requestUri(),
                detailResponse.statusCode(),
                detailResponse.contentType(),
                detailResponse.responseType(),
                detailResponse.bodyLength(),
                detailResponse.bodyPreview(500)
        );
    }

    public static void validateDetailResponse(DetailResponse response, String message) {
        if (response != null && response.isKboErrorPage()) {
            log.warn(
                    "[KboGameDetailClient] {} method={} uri={} status={} contentType={} responseType={} bodyLength={} bodyPreview={}",
                    message,
                    response.method(),
                    response.requestUri(),
                    response.statusCode(),
                    response.contentType(),
                    response.responseType(),
                    response.bodyLength(),
                    response.bodyPreview(5000)
            );
            throw new KboGameDetailEndpointException(message);
        }
    }

    public record DetailResponse(
            String body,
            Integer statusCode,
            String contentType,
            String requestUri,
            String method
    ) {
        public int bodyLength() {
            return body == null ? 0 : body.length();
        }

        public String bodyPreview(int maxCharacters) {
            if (body == null) {
                return null;
            }
            String normalized = body.replace("\r", "\\r").replace("\n", "\\n");
            return normalized.length() <= maxCharacters ? normalized : normalized.substring(0, maxCharacters);
        }

        public String responseType() {
            if (body == null || body.isBlank()) {
                return "empty";
            }
            String trimmed = body.trim();
            String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
            if (normalizedContentType.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return "json";
            }
            if (normalizedContentType.contains("html") || trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html") || trimmed.contains("<html")) {
                return "html";
            }
            return "unknown";
        }

        public boolean isKboErrorPage() {
            if (body == null) {
                return false;
            }
            String normalized = body.toLowerCase(Locale.ROOT);
            return normalized.contains("<title>에러 | kbo홈페이지")
                    || normalized.contains("errorbox")
                    || normalized.contains("이용에 불편을 드려 죄송합니다")
                    || normalized.contains("kbo홈페이지 </title>")
                    || normalized.contains("object moved")
                    || normalized.contains("입력 문자열의 형식이 잘못되었습니다")
                    || normalized.contains("input string was not in a correct format");
        }
    }

    public static class KboGameDetailEndpointException extends IllegalStateException {
        public KboGameDetailEndpointException(String message) {
            super(message);
        }
    }
}
