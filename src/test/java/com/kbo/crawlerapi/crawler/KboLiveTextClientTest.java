package com.kbo.crawlerapi.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KboLiveTextClientTest {

    @Test
    void postsLiveTextView2WithRequiredHeadersAndFormWithoutCookies() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KboLiveTextClient client = new TestableKboLiveTextClient(builder, "https://www.koreabaseball.com");

        server.expect(requestTo("https://www.koreabaseball.com/Game/LiveTextView2.aspx"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.USER_AGENT, containsString("Mozilla/5.0")))
                .andExpect(header(HttpHeaders.ORIGIN, "https://www.koreabaseball.com"))
                .andExpect(header(HttpHeaders.REFERER, "https://www.koreabaseball.com/Game/LiveText.aspx?leagueId=1&seriesId=0&gameId=20260529LTNC0&gyear=2026"))
                .andExpect(header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(request -> assertThat(request.getHeaders()).doesNotContainKey(HttpHeaders.COOKIE))
                .andExpect(content().contentType(MediaType.parseMediaType("application/x-www-form-urlencoded;charset=UTF-8")))
                .andExpect(content().string(containsString("leagueId=1")))
                .andExpect(content().string(containsString("seriesId=0")))
                .andExpect(content().string(containsString("gameId=20260529LTNC0")))
                .andExpect(content().string(containsString("gyear=2026")))
                .andRespond(withSuccess("<html></html>", MediaType.TEXT_HTML));

        client.fetchLiveText("20260529LTNC0", 2026);

        server.verify();
    }

    private static final class TestableKboLiveTextClient extends KboLiveTextClient {
        private TestableKboLiveTextClient(RestClient.Builder restClientBuilder, String baseUrl) {
            super(restClientBuilder, baseUrl);
        }
    }
}
