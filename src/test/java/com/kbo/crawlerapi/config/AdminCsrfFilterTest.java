package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class AdminCsrfFilterTest {

    private final AdminCsrfFilter filter = new AdminCsrfFilter();

    @Test
    void getCreatesStableSessionTokenAndDifferentSessionsHaveDifferentTokens() throws Exception {
        var session = new MockHttpSession();
        var first = request("GET", "/admin/login", session);
        assertThat(apply(first)).isEqualTo(200);
        String token = (String) session.getAttribute(AdminCsrfFilter.TOKEN_SESSION_ATTRIBUTE);
        assertThat(token).isNotBlank();
        assertThat(apply(request("GET", "/admin/dashboard", session))).isEqualTo(200);
        assertThat(session.getAttribute(AdminCsrfFilter.TOKEN_SESSION_ATTRIBUTE)).isEqualTo(token);

        var other = request("GET", "/admin/login", new MockHttpSession());
        assertThat(apply(other)).isEqualTo(200);
        assertThat(other.getSession().getAttribute(AdminCsrfFilter.TOKEN_SESSION_ATTRIBUTE)).isNotEqualTo(token);
    }

    @Test
    void unsafeMethodsRequireTheExistingSessionToken() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute(AdminCsrfFilter.TOKEN_SESSION_ATTRIBUTE, "session-token");
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            var missing = request(method, "/admin/schedule/import", session);
            assertThat(apply(missing)).isEqualTo(403);
            var wrong = request(method, "/admin/schedule/import", session);
            wrong.addParameter("_csrf", "another-session-token");
            assertThat(apply(wrong)).isEqualTo(403);
            var valid = request(method, "/admin/schedule/import", session);
            valid.addParameter("_csrf", "session-token");
            assertThat(apply(valid)).isEqualTo(200);
        }
    }

    @Test
    void loginWithoutSessionFailsWithoutCreatingOneAndKeyHeaderAloneCannotBypassCsrf() throws Exception {
        var request = new MockHttpServletRequest("POST", "/admin/login");
        request.addHeader(AdminApiKeyFilter.ADMIN_KEY_HEADER, "unverified-key");
        request.addParameter("_csrf", "arbitrary-token");
        assertThat(apply(request)).isEqualTo(403);
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void verifiedApiKeyCanMutateWithoutSessionAndAssetsDoNotCreateSessions() throws Exception {
        var request = new MockHttpServletRequest("POST", "/admin/notifications/manual");
        request.setAttribute(AdminApiKeyFilter.API_KEY_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
        assertThat(apply(request)).isEqualTo(200);
        assertThat(request.getSession(false)).isNull();
        var asset = new MockHttpServletRequest("GET", "/admin/assets/admin.js");
        assertThat(apply(asset)).isEqualTo(200);
        assertThat(asset.getSession(false)).isNull();
    }

    private MockHttpServletRequest request(String method, String path, MockHttpSession session) {
        var request = new MockHttpServletRequest(method, path);
        request.setSession(session);
        return request;
    }

    private int apply(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        boolean[] reached = {false};
        filter.doFilter(request, response, (req, res) -> reached[0] = true);
        assertThat(reached[0]).isEqualTo(response.getStatus() == 200);
        return response.getStatus();
    }
}
