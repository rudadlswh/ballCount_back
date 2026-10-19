package com.kbo.crawlerapi.crawler;

import com.kbo.crawlerapi.config.KboHttpClientFactory;
import com.kbo.crawlerapi.config.KboHttpProperties;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class KboLiveTextClient {

    private static final Logger log = LoggerFactory.getLogger(KboLiveTextClient.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String BASE_URL = "https://www.koreabaseball.com";
    private static final String LIVE_TEXT_ENDPOINT_PATH = "/Game/LiveTextView2.aspx";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private final RestClient restClient;
    private final String baseUrl;

    public KboLiveTextClient() {
        this(new KboHttpProperties());
    }

    @Autowired
    public KboLiveTextClient(KboHttpProperties httpProperties) {
        this(RestClient.builder(), BASE_URL, httpProperties);
    }

    protected KboLiveTextClient(RestClient.Builder restClientBuilder, String baseUrl) {
        this(restClientBuilder, baseUrl, new KboHttpProperties(), false);
    }

    protected KboLiveTextClient(RestClient.Builder restClientBuilder, String baseUrl, KboHttpProperties httpProperties) {
        this(restClientBuilder, baseUrl, httpProperties, true);
    }

    private KboLiveTextClient(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            KboHttpProperties httpProperties,
            boolean configureRequestFactory
    ) {
        RestClient.Builder builder = restClientBuilder.baseUrl(baseUrl);
        if (configureRequestFactory) {
            builder.requestFactory(KboHttpClientFactory.restClientRequestFactory(httpProperties));
        }
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
    }

    public LiveTextResponse fetchLiveText(String providerGameId, int season) {
        return fetchLiveText(providerGameId, season, 1, 0);
    }

    public LiveTextResponse fetchLiveText(String providerGameId, int season, int leagueId, int seriesId) {
        String referer = "%s/Game/LiveText.aspx?leagueId=%d&seriesId=%d&gameId=%s&gyear=%d"
                .formatted(baseUrl, leagueId, seriesId, providerGameId, season);
        String requestUri = URI.create(baseUrl + LIVE_TEXT_ENDPOINT_PATH).toString();
        log.info(
                "[KboLiveText] request start providerGameId={} season={} url={}",
                providerGameId,
                season,
                requestUri
        );

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("leagueId", String.valueOf(leagueId));
        form.add("seriesId", String.valueOf(seriesId));
        form.add("gameId", providerGameId);
        form.add("gyear", String.valueOf(season));

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(LIVE_TEXT_ENDPOINT_PATH)
                    .contentType(MediaType.parseMediaType("application/x-www-form-urlencoded; charset=UTF-8"))
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .header(HttpHeaders.ORIGIN, baseUrl)
                    .header(HttpHeaders.REFERER, referer)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .body(form)
                    .retrieve()
                    .toEntity(String.class);
            LiveTextResponse liveTextResponse = new LiveTextResponse(
                    response.getBody(),
                    response.getStatusCode().value(),
                    response.getHeaders().getContentType() == null ? null : response.getHeaders().getContentType().toString(),
                    requestUri,
                    "POST"
            );
            log.info(
                    "[KboLiveText] response providerGameId={} season={} url={} status={} contentType={} bodyLength={}",
                    providerGameId,
                    season,
                    liveTextResponse.requestUri(),
                    liveTextResponse.statusCode(),
                    liveTextResponse.contentType(),
                    liveTextResponse.bodyLength()
            );
            return liveTextResponse;
        } catch (RestClientException exception) {
            throw new IllegalStateException("Failed to fetch KBO live text for " + providerGameId, exception);
        }
    }

    public record LiveTextResponse(
            String body,
            Integer statusCode,
            String contentType,
            String requestUri,
            String method
    ) {
        public int bodyLength() {
            return body == null ? 0 : body.length();
        }

        public String responseType() {
            if (body == null || body.isBlank()) {
                return "empty";
            }
            String trimmed = body.trim();
            String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
            if (normalizedContentType.contains("html") || trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html") || trimmed.contains("<table")) {
                return "html";
            }
            return "unknown";
        }
    }
}
