package com.kbo.crawlerapi.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.kbo.crawlerapi.config.AdminApiKeyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminAuthenticationControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminUiProperties properties = new AdminUiProperties();
        properties.setUsername("operator");
        properties.setPassword("very-secret");
        AdminAuthenticationService service = new AdminAuthenticationService(properties);
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
    void invalidLoginReturnsGenericUnauthorizedPage() throws Exception {
        mockMvc.perform(post("/admin/login")
                        .param("username", "operator")
                        .param("password", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(view().name("admin/login"))
                .andExpect(model().attributeExists("loginError"));
    }
}
