package com.kbo.crawlerapi.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.service.LiveActivityTokenRegistrationService;
import com.kbo.crawlerapi.service.LiveActivityTokenRegistrationService.LiveActivityTokenRegistrationCommand;
import com.kbo.crawlerapi.service.LiveActivityTokenRegistrationService.LiveActivityTokenRegistrationResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LiveActivityTokenControllerTest {

    @Test
    void postDevicesLiveActivitiesRegisterReturnsSuccess() throws Exception {
        LiveActivityTokenRegistrationService service = new StubLiveActivityTokenRegistrationService();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LiveActivityTokenController(service)).build();

        mvc.perform(post("/devices/live-activities/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "activityId": "activity-1",
                                  "platform": "ios",
                                  "environment": "sandbox",
                                  "activityToken": "abcdef1234567890",
                                  "publicGameId": "20260605-LOT-HAN",
                                  "providerGameId": "20260605HHLT0",
                                  "databaseId": "22222222-2222-2222-2222-222222222222",
                                  "stableDetailIdentity": "provider:20260605HHLT0"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("sandbox"))
                .andExpect(jsonPath("$.active").value(true));
    }

    private static final class StubLiveActivityTokenRegistrationService extends LiveActivityTokenRegistrationService {
        private StubLiveActivityTokenRegistrationService() {
            super(null, java.time.Clock.systemUTC());
        }

        @Override
        public LiveActivityTokenRegistrationResult register(LiveActivityTokenRegistrationCommand command) {
            return new LiveActivityTokenRegistrationResult(
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    command.activityId(),
                    "sandbox",
                    "abcdef123456",
                    true
            );
        }
    }
}
