package com.kbo.crawlerapi.crawler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KboGameDetailClientTest {

    @Test
    void kboErrorHtmlIsReportedClearly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KboGameDetailClient client = new KboGameDetailClient(builder, "https://www.koreabaseball.com");

        server.expect(requestTo("https://www.koreabaseball.com/ws/Main.asmx/GetKboGameList"))
                .andRespond(withSuccess(kboErrorHtml(), MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.fetchGameListResponse(LocalDate.of(2026, 5, 21)))
                .isInstanceOf(KboGameDetailClient.KboGameDetailEndpointException.class)
                .hasMessage("KBO game detail list endpoint returned error page");
        server.verify();
    }

    private String kboErrorHtml() {
        return """
                <!DOCTYPE html>
                <html lang="ko">
                <head><title>에러 | KBO홈페이지 </title></head>
                <body>
                  <div class="errorbox">
                    <strong>이용에 불편을 드려 죄송합니다.</strong>
                  </div>
                </body>
                </html>
                """;
    }
}
