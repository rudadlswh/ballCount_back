package com.kbo.crawlerapi.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.service.AttendanceService;
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
                .andExpect(jsonPath("$.gameIds[0]").value("22222222-2222-2222-2222-222222222222"));
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
        public List<UUID> listGameIds(String installationId) {
            return List.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        }
    }
}
