package com.kbo.crawlerapi.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicPageControllerWebMvcTest {

    private static final String SUPPORT_EMAIL = "whrudals56@gmail.com";
    private static final MediaType TEXT_HTML_UTF8 = MediaType.parseMediaType("text/html;charset=UTF-8");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicPageController()).build();
    }

    @Test
    void supportReturnsPublicHtmlPage() throws Exception {
        mockMvc.perform(get("/support"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(TEXT_HTML_UTF8))
                .andExpect(content().string(containsString("볼카운트")))
                .andExpect(content().string(containsString("문의")))
                .andExpect(content().string(containsString(SUPPORT_EMAIL)));
    }

    @Test
    void privacyReturnsPublicHtmlPage() throws Exception {
        mockMvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(TEXT_HTML_UTF8))
                .andExpect(content().string(containsString("개인정보 처리방침")))
                .andExpect(content().string(containsString("기기 토큰")))
                .andExpect(content().string(containsString("제3자")));
    }
}
