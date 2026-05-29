package com.kbo.crawlerapi.crawler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KboGameDetailClientTest {

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
}
