package com.kbo.crawlerapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kbo.crawlerapi.config.AdminApiKeyFilter;
import com.kbo.crawlerapi.config.AdminCsrfFilter;
import com.kbo.crawlerapi.config.AppSecurityProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminGameEditingTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"), ZoneOffset.UTC);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AdminDataService service = new AdminDataService(jdbc, null, null, null, null, null, null, clock);

    @Test
    void cancellationUpdatesFlagsAndReasonAndCanBeCleared() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var now = OffsetDateTime.now(clock);
        assertThat(service.updateGame("20261028-KIA-SAM", LocalTime.of(18, 30), " 광주 ",
                "cancelled", " 일정 변경 ", "etc")).isTrue();
        verify(jdbc).update(contains("UPDATE games"), eq("18:30"), eq("18:30"), eq("광주"), eq("cancelled"),
                eq("일정 변경"), eq(true), eq(false), eq("etc"), eq("일정 변경"), eq("cancelled"), eq(now), eq(now), eq("20261028-KIA-SAM"));
        service.updateGame("20261028-KIA-SAM", null, "광주", "scheduled", "", "rain");
        verify(jdbc).update(contains("UPDATE games"), isNull(), isNull(), eq("광주"), eq("scheduled"),
                isNull(), eq(false), eq(false), isNull(), isNull(), eq("scheduled"), eq(now), eq(now), eq("20261028-KIA-SAM"));
    }

    @Test
    void invalidInputNeverWritesToDatabase() {
        assertThatThrownBy(() -> service.updateGame("id", null, "광주", "invalid", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateGame("id", null, "a".repeat(101), "scheduled", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateGame("id", null, "광주", "cancelled", null, "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void editorRequiresLoginAndCsrfAndSavesWithValidSession() throws Exception {
        var data = mock(AdminDataService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminPageController(data, null, null, null))
                .addFilters(new AdminApiKeyFilter(new AppSecurityProperties()), new AdminCsrfFilter()).build();
        mvc.perform(post("/admin/games/id").param("status", "cancelled"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/games/id").param("status", "cancelled")
                        .sessionAttr(AdminApiKeyFilter.ADMIN_SESSION_ATTRIBUTE, true))
                .andExpect(status().isForbidden());
        verifyNoInteractions(data);
        when(data.updateGame("id", LocalTime.of(18, 30), "광주", "cancelled", "일정 변경", "etc")).thenReturn(true);
        mvc.perform(post("/admin/games/id").param("status", "cancelled").param("scheduledTime", "18:30")
                        .param("stadium", "광주").param("statusReason", "일정 변경").param("cancelReason", "etc")
                        .param("_csrf", "token").sessionAttr(AdminApiKeyFilter.ADMIN_SESSION_ATTRIBUTE, true)
                        .sessionAttr(AdminCsrfFilter.TOKEN_SESSION_ATTRIBUTE, "token"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/games/id"))
                .andExpect(flash().attributeExists("gameEditSuccess"));
    }

    @Test
    void editorShowsValidationErrorsAndMissingGameIs404() throws Exception {
        var data = mock(AdminDataService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminPageController(data, null, null, null)).build();
        when(data.updateGame("id", null, null, "bad", null, null)).thenThrow(new IllegalArgumentException("잘못된 상태"));
        mvc.perform(post("/admin/games/id").param("status", "bad"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("gameEditError", "잘못된 상태"));
        mvc.perform(post("/admin/games/missing").param("status", "scheduled"))
                .andExpect(status().isNotFound());
    }
}
