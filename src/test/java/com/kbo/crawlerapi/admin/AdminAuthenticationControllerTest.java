package com.kbo.crawlerapi.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.kbo.crawlerapi.config.AdminApiKeyFilter;
import com.kbo.crawlerapi.config.AdminCsrfFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminAuthenticationControllerTest {

    private MockMvc mockMvc;
    private AdminAuthenticationService service;

    @BeforeEach
    void setUp() {
        AdminUiProperties properties = new AdminUiProperties();
        properties.setUsername("operator");
        properties.setPassword("very-secret");
        service = new AdminAuthenticationService(properties);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminAuthenticationController(service)).build();
    }

    @Test
    void loginPageIsRendered() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/login"))
                .andExpect(model().attribute("configurationMissing", false));
    }

    @Test
    void validLoginCreatesAuthenticatedSessionAndRedirects() throws Exception {
        mockMvc.perform(post("/admin/login")
                        .param("username", "operator")
                        .param("password", "very-secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"))
                .andExpect(request().sessionAttribute(AdminApiKeyFilter.ADMIN_SESSION_ATTRIBUTE, true));
    }

    @Test
    void repeatedLoginWithThePreviousTokenRedirectsInsteadOfShowingForbidden() throws Exception {
        var guarded = MockMvcBuilders.standaloneSetup(new AdminAuthenticationController(service))
                .addFilters(new AdminCsrfFilter()).build();
        var page = guarded.perform(get("/admin/login")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) page.getRequest().getSession(false);
        var loginToken = (String) session.getAttribute(AdminCsrfFilter.TOKEN_SESSION_ATTRIBUTE);

        guarded.perform(post("/admin/login").session(session)
                        .param("username", "operator").param("password", "very-secret")
                        .param("_csrf", loginToken))
                .andExpect(status().isFound())
                .andExpect(request().sessionAttribute(AdminApiKeyFilter.ADMIN_SESSION_ATTRIBUTE, true));

        guarded.perform(post("/admin/login").session(session)
                        .param("username", "operator").param("password", "very-secret")
                        .param("_csrf", loginToken))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/admin/dashboard"));
    }

    @Test
    void invalidLoginReturnsGenericUnauthorizedPage() throws Exception {
        mockMvc.perform(post("/admin/login")
                        .param("username", "operator")
                        .param("password", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(view().name("admin/login"))
                .andExpect(model().attributeExists("loginError"));
    }
}
