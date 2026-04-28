package com.kbo.crawlerapi.crawler;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class KboGameDetailClient {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient restClient;

    public KboGameDetailClient() {
        this.restClient = RestClient.builder()
                .baseUrl("https://www.koreabaseball.com")
                .build();
    }

    public String fetchGameList(LocalDate gameDate) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("leId", "1");
        form.add("srId", "0,1,3,4,5,6,7,9");
        form.add("date", gameDate.format(DATE_FORMATTER));

        try {
            return restClient.post()
                    .uri("/ws/Main.asmx/GetKboGameList")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
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
            return restClient.post()
                    .uri("/ws/Schedule.asmx/GetScoreBoardScroll")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Failed to fetch KBO line score for " + providerGameId, exception);
        }
    }

    public String fetchBoxScore(String providerGameId, int seasonId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("leId", "1");
        form.add("srId", "0");
        form.add("seasonId", String.valueOf(seasonId));
        form.add("gameId", providerGameId);

        try {
            return restClient.post()
                    .uri("/ws/Schedule.asmx/GetBoxScoreScroll")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Failed to fetch KBO box score for " + providerGameId, exception);
        }
    }
}
