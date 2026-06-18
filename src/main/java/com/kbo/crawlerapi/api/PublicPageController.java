package com.kbo.crawlerapi.api;

import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicPageController {

    private static final String SUPPORT_EMAIL = "chogyeongmin.dev@gmail.com";
    private static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);

    @GetMapping("/support")
    public ResponseEntity<String> support() {
        return html("""
                <h1>볼카운트 지원</h1>

                <p>볼카운트는 KBO 경기 일정, 실시간 스코어, 순위, 경기 알림을 제공하는 야구 스코어 앱입니다.</p>

                <h2>문의</h2>
                <p>앱 이용 중 문제가 있거나 문의가 필요한 경우 아래 이메일로 연락해 주세요.</p>
                %s

                <h2>주요 기능</h2>
                <ul>
                  <li>KBO 경기 일정 확인</li>
                  <li>실시간 스코어 확인</li>
                  <li>팀 순위 확인</li>
                  <li>응원팀 기반 경기 알림</li>
                  <li>Live Activity 지원</li>
                </ul>

                <h2>안내</h2>
                <p>볼카운트는 KBO 및 각 구단의 공식 앱이 아닙니다. 앱 내 정보는 경기 확인 편의를 위한 참고용으로 제공됩니다.</p>
                """.formatted(emailLink()));
    }

    @GetMapping("/privacy")
    public ResponseEntity<String> privacy() {
        return html("""
                <h1>볼카운트 개인정보 처리방침</h1>

                <p>볼카운트는 KBO 경기 일정, 실시간 스코어, 순위, 경기 알림을 제공하는 야구 스코어 앱입니다.</p>

                <h2>수집 또는 사용하는 정보</h2>
                <ul>
                  <li>응원팀 설정 정보</li>
                  <li>알림 설정 정보</li>
                  <li>푸시 알림 전송을 위한 기기 토큰</li>
                  <li>앱 오류 및 진단 정보</li>
                </ul>

                <h2>사용 목적</h2>
                <ul>
                  <li>KBO 경기 알림 제공</li>
                  <li>응원팀 기반 알림 제공</li>
                  <li>앱 기능 개선</li>
                  <li>오류 확인 및 안정성 개선</li>
                </ul>

                <h2>제3자 제공</h2>
                <p>볼카운트는 사용자의 개인정보를 판매하지 않으며, 법령에 따른 경우를 제외하고 개인정보를 제3자에게 제공하지 않습니다.</p>

                <h2>보관 및 삭제</h2>
                <p>앱 삭제, 알림 비활성화 또는 삭제 요청 시 관련 정보는 더 이상 서비스 제공에 사용되지 않습니다. 개인정보 삭제 요청은 문의 이메일을 통해 요청할 수 있습니다.</p>

                <h2>문의</h2>
                <p>개인정보 처리방침 또는 앱 이용 관련 문의는 아래 이메일로 연락해 주세요.</p>
                %s

                <p>최종 업데이트: 2026년 6월 18일</p>
                """.formatted(emailLink()));
    }

    private ResponseEntity<String> html(String body) {
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .body(page(body));
    }

    private static String emailLink() {
        return "<p><a href=\"mailto:%s\">%s</a></p>".formatted(SUPPORT_EMAIL, SUPPORT_EMAIL);
    }

    private static String page(String body) {
        return """
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>볼카운트</title>
                  <style>
                    :root {
                      color-scheme: dark;
                      --background: #0B0F16;
                      --surface: #171C24;
                      --text: #FFFFFF;
                      --muted: #C8CDD6;
                      --accent: #D8B98C;
                      --border: #2A313D;
                    }

                    * {
                      box-sizing: border-box;
                    }

                    body {
                      margin: 0;
                      min-height: 100vh;
                      background: var(--background);
                      color: var(--text);
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                      line-height: 1.7;
                    }

                    main {
                      width: min(760px, calc(100%% - 32px));
                      margin: 0 auto;
                      padding: 48px 0;
                    }

                    article {
                      background: var(--surface);
                      border: 1px solid var(--border);
                      border-radius: 8px;
                      padding: 40px;
                    }

                    h1 {
                      margin: 0 0 24px;
                      color: var(--text);
                      font-size: clamp(2rem, 6vw, 3rem);
                      line-height: 1.2;
                    }

                    h2 {
                      margin: 32px 0 12px;
                      color: var(--accent);
                      font-size: 1.25rem;
                      line-height: 1.35;
                    }

                    p {
                      margin: 0 0 16px;
                      color: var(--muted);
                    }

                    ul {
                      margin: 0 0 16px;
                      padding-left: 1.4rem;
                      color: var(--muted);
                    }

                    a {
                      color: var(--accent);
                      overflow-wrap: anywhere;
                    }

                    @media (max-width: 600px) {
                      main {
                        width: min(100%% - 24px, 760px);
                        padding: 24px 0;
                      }

                      article {
                        padding: 28px 20px;
                      }
                    }
                  </style>
                </head>
                <body>
                  <main>
                    <article>
                %s
                    </article>
                  </main>
                </body>
                </html>
                """.formatted(body);
    }
}

