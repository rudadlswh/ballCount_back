package com.kbo.crawlerapi.crawler;

import java.time.YearMonth;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Component
public class KboScheduleClient {

    private static final Logger log = LoggerFactory.getLogger(KboScheduleClient.class);
    private static final String BASE_URL = "https://www.koreabaseball.com";
    private static final String SCHEDULE_PAGE_URL = BASE_URL + "/Schedule/Schedule.aspx";
    private static final String SCHEDULE_ENDPOINT_PATH = "/ws/Schedule.asmx/GetScheduleList";
    private static final String METHOD = "POST";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final String ACCEPT = "application/json, text/javascript, */*; q=0.01";
    private static final String ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7";

    private final RestClient restClient;
    private final String scheduleEndpointUri;

    public KboScheduleClient() {
        this(RestClient.builder(), BASE_URL);
    }

    protected KboScheduleClient(RestClient.Builder restClientBuilder, String baseUrl) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .build();
        this.scheduleEndpointUri = baseUrl + SCHEDULE_ENDPOINT_PATH;
    }

    public String fetchMonthlySchedule(YearMonth yearMonth) {
        return fetchMonthlyScheduleResponse(yearMonth).body();
    }

    public ScheduleResponse fetchMonthlyScheduleResponse(YearMonth yearMonth) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("leId", "1");
        form.add("srIdList", "0,9,6");
        form.add("seasonId", String.valueOf(yearMonth.getYear()));
        form.add("gameMonth", String.format("%02d", yearMonth.getMonthValue()));
        form.add("teamId", "");

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(SCHEDULE_ENDPOINT_PATH)
                    .contentType(MediaType.parseMediaType("application/x-www-form-urlencoded; charset=UTF-8"))
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .header(HttpHeaders.ACCEPT, ACCEPT)
                    .header(HttpHeaders.ACCEPT_LANGUAGE, ACCEPT_LANGUAGE)
                    .header(HttpHeaders.REFERER, SCHEDULE_PAGE_URL)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .body(form)
                    .retrieve()
                    .toEntity(String.class);
            ScheduleResponse scheduleResponse = new ScheduleResponse(
                    response.getBody(),
                    response.getStatusCode().value(),
                    response.getHeaders().getContentType() == null ? null : response.getHeaders().getContentType().toString(),
                    scheduleEndpointUri,
                    METHOD,
                    response.getHeaders().getLocation() == null ? null : response.getHeaders().getLocation().toString()
            );
            log.debug(
                    "[KboScheduleClient] response method={} uri={} status={} contentType={} bodyLength={} redirectLocation={}",
                    METHOD,
                    scheduleEndpointUri,
                    scheduleResponse.statusCode(),
                    scheduleResponse.contentType(),
                    scheduleResponse.bodyLength(),
                    scheduleResponse.redirectLocation()
            );
            validateScheduleResponse(scheduleResponse);
            return scheduleResponse;
        } catch (RestClientException exception) {
            log.warn(
                    "[KboScheduleClient] request failed method={} uri={} reason={}",
                    METHOD,
                    scheduleEndpointUri,
                    exception.getMessage()
            );
            throw new IllegalStateException("Failed to fetch KBO monthly schedule for " + yearMonth, exception);
        }
    }

    public static void validateScheduleResponse(ScheduleResponse response) {
        if (response != null && response.isKboErrorPage()) {
            log.warn(
                    "[KboScheduleClient] KBO schedule endpoint returned error page method={} uri={} status={} contentType={} bodyLength={} redirectLocation={} bodyPreview={}",
                    response.method(),
                    response.requestUri(),
                    response.statusCode(),
                    response.contentType(),
                    response.bodyLength(),
                    response.redirectLocation(),
                    response.bodyPreview(5000)
            );
            throw new KboScheduleEndpointException("KBO schedule endpoint returned error page");
        }
    }

    public record ScheduleResponse(
            String body,
            Integer statusCode,
            String contentType,
            String requestUri,
            String method,
            String redirectLocation
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

        public boolean isKboErrorPage() {
            if (body == null) {
                return false;
            }
            String normalized = body.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("<title>에러 | kbo홈페이지")
                    || normalized.contains("errorbox")
                    || normalized.contains("이용에 불편을 드려 죄송합니다")
                    || normalized.contains("kbo홈페이지 </title>");
        }
    }

    public static class KboScheduleEndpointException extends IllegalStateException {
        public KboScheduleEndpointException(String message) {
            super(message);
        }
    }
}
