package com.kbo.crawlerapi.crawler;

import java.time.YearMonth;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Component
public class KboScheduleClient {

    private final RestClient restClient;

    public KboScheduleClient() {
        this.restClient = RestClient.builder()
                .baseUrl("https://www.koreabaseball.com")
                .build();
    }

    public String fetchMonthlySchedule(YearMonth yearMonth) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("leId", "1");
        form.add("srIdList", "0,9,6");
        form.add("seasonId", String.valueOf(yearMonth.getYear()));
        form.add("gameMonth", String.format("%02d", yearMonth.getMonthValue()));
        form.add("teamId", "");

        try {
            return restClient.post()
                    .uri("/ws/Schedule.asmx/GetScheduleList")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Failed to fetch KBO monthly schedule for " + yearMonth, exception);
        }
    }
}
