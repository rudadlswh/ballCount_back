package com.kbo.crawlerapi.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.service.AttendanceService;
import com.kbo.crawlerapi.api.dto.AttendanceGameDto;
import com.kbo.crawlerapi.api.dto.AttendanceListResponse;
import com.kbo.crawlerapi.api.dto.TeamSummaryDto;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AttendanceControllerWebMvcTest {

    private MockMvc mockMvc;
    private RecordingAttendanceService attendanceService;

    @BeforeEach
    void setUp() {
        attendanceService = new RecordingAttendanceService();
        mockMvc = MockMvcBuilders.standaloneSetup(new AttendanceController(attendanceService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void postUpsertsAttendance() throws Exception {
        mockMvc.perform(post("/api/v1/attendance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "installationId": "11111111-1111-1111-1111-111111111111",
                                  "gameId": "22222222-2222-2222-2222-222222222222"
                                }
                                """))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(attendanceService.lastAction).isEqualTo("upsert");
    }

    @Test
    void deleteRemovesAttendance() throws Exception {
        mockMvc.perform(delete("/api/v1/attendance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "installationId": "11111111-1111-1111-1111-111111111111",
                                  "gameId": "22222222-2222-2222-2222-222222222222"
                                }
                                """))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(attendanceService.lastAction).isEqualTo("delete");
    }

    @Test
    void getListsGameIds() throws Exception {
        mockMvc.perform(get("/api/v1/attendance")
                        .param("installationId", "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameIds[0]").value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.records[0].publicGameId").value("20260720-LG-SSG"))
                .andExpect(jsonPath("$.records[0].awayTeam.id").value("lg"))
                .andExpect(jsonPath("$.records[0].homeScore").value(2));
    }

    private static final class RecordingAttendanceService extends AttendanceService {
        private String lastAction;

        private RecordingAttendanceService() {
            super(null);
        }

        @Override
        public void upsert(String installationId, String gameId) {
            lastAction = "upsert";
        }

        @Override
        public void delete(String installationId, String gameId) {
            lastAction = "delete";
        }

        @Override
        public AttendanceListResponse list(String installationId) {
            UUID gameId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            return new AttendanceListResponse(
                    List.of(gameId),
                    List.of(new AttendanceGameDto(
                            gameId,
                            "20260720-LG-SSG",
                            LocalDate.of(2026, 7, 20),
                            OffsetDateTime.parse("2026-07-20T18:30:00+09:00"),
                            "잠실",
                            "final",
                            false,
                            false,
                            new TeamSummaryDto("lg", "LG 트윈스", "LG", null),
                            new TeamSummaryDto("ssg", "SSG 랜더스", "SSG", null),
                            4,
                            2
                    ))
            );
        }
    }
}
