package com.kbo.crawlerapi.crawler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KboGameDetailClientTest {

    @Test
    void gameListRequestUsesRequiredBrowserHeadersAndForm() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KboGameDetailClient client = new TestableKboGameDetailClient(builder, "https://www.koreabaseball.com");

        server.expect(requestTo("https://www.koreabaseball.com/ws/Main.asmx/GetKboGameList"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.USER_AGENT, containsString("Mozilla/5.0")))
                .andExpect(header(HttpHeaders.ACCEPT, "application/json, text/javascript, */*; q=0.01"))
                .andExpect(header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"))
                .andExpect(header(HttpHeaders.REFERER, "https://www.koreabaseball.com/Schedule/GameCenter/Main.aspx"))
                .andExpect(header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(content().contentType(MediaType.parseMediaType("application/x-www-form-urlencoded;charset=UTF-8")))
                .andExpect(content().string(containsString("date=20260602")))
                .andExpect(content().string(containsString("leId=1")))
                .andExpect(content().string(containsString("srId=0%2C1%2C3%2C4%2C5%2C6%2C7%2C8%2C9")))
                .andRespond(withSuccess("{\"game\":[]}", MediaType.APPLICATION_JSON));

        client.fetchGameListResponse(LocalDate.of(2026, 6, 2));

        server.verify();
    }

    @Test
    void kboErrorHtmlIsReportedClearly() {
        KboGameDetailClient.DetailResponse response = new KboGameDetailClient.DetailResponse(
                kboErrorHtml(),
                200,
                "text/html",
                "https://www.koreabaseball.com/ws/Main.asmx/GetKboGameList",
                "POST"
        );

        assertThatThrownBy(() -> KboGameDetailClient.validateDetailResponse(response, "KBO game detail list endpoint returned error page"))
                .isInstanceOf(KboGameDetailClient.KboGameDetailEndpointException.class)
                .hasMessage("KBO game detail list endpoint returned error page");
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

    private static final class TestableKboGameDetailClient extends KboGameDetailClient {
        private TestableKboGameDetailClient(RestClient.Builder restClientBuilder, String baseUrl) {
            super(restClientBuilder, baseUrl, false);
        }
    }
}
