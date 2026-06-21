package com.kbo.crawlerapi.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.api.PublicPageController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductionSwaggerUiBlockFilterTest {

    @Test
    void swaggerUiHtmlIsNotFoundWhenProductionFilterIsApplied() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PublicPageController())
                .addFilters(new ProductionSwaggerUiBlockFilter())
                .build();

        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isNotFound());
    }
}
