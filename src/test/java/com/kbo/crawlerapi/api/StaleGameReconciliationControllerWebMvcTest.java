package com.kbo.crawlerapi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kbo.crawlerapi.config.AdminApiKeyFilter;
import com.kbo.crawlerapi.config.AppSecurityProperties;
import com.kbo.crawlerapi.config.KboAdminProperties;
import com.kbo.crawlerapi.config.KboReconcileProperties;
import com.kbo.crawlerapi.config.RegistrationRequestSizeLimitFilter;
import com.kbo.crawlerapi.service.LiveGameSyncService.LiveSyncSummary;
import com.kbo.crawlerapi.service.StaleGameReconciliationService;
import com.kbo.crawlerapi.service.StaleGameReconciliationService.StaleGameReconciliationDateResult;
import com.kbo.crawlerapi.service.StaleGameReconciliationService.StaleGameReconciliationDateStatus;
import com.kbo.crawlerapi.service.StaleGameReconciliationService.StaleGameReconciliationResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StaleGameReconciliationControllerWebMvcTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-19T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String ADMIN_KEY = "test-admin-key";

    private RecordingStaleGameReconciliationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = new RecordingStaleGameReconciliationService();
        mockMvc = mockMvc(new StaleGameReconciliationController(service));
    }

    @Test
    void reconcileStaleGamesPassesRequestedDatesToService() throws Exception {
        mockMvc.perform(post("/api/v1/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-04-16","2026-04-15"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates[0]").value("2026-04-15"))
                .andExpect(jsonPath("$.dates[1]").value("2026-04-16"))
                .andExpect(jsonPath("$.processedDateCount").value(2))
                .andExpect(jsonPath("$.skippedAlreadyInProgressCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.dateResults[0].status").value("PROCESSED"));

        assertThat(service.requestedDates).containsExactly(
                LocalDate.of(2026, 4, 16),
                LocalDate.of(2026, 4, 15)
        );
    }

    @Test
    void reconcileStaleGamesAlsoSupportsSuggestedUnversionedPath() throws Exception {
        mockMvc.perform(post("/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-04-16"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates[0]").value("2026-04-16"));
    }

    @Test
    void reconcileStaleGamesRejectsEmptyDates() throws Exception {
        mockMvc.perform(post("/api/v1/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void publicReconcileRejectsMoreThanThreeUniqueDates() throws Exception {
        MockMvc publicMockMvc = mockMvc(new StaleGameReconciliationController(realService(new CountingLiveGameSyncService())));

        publicMockMvc.perform(post("/api/v1/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-06-16","2026-06-17","2026-06-18","2026-06-19"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void publicReconcileRejectsFutureDate() throws Exception {
        MockMvc publicMockMvc = mockMvc(new StaleGameReconciliationController(realService(new CountingLiveGameSyncService())));

        publicMockMvc.perform(post("/api/v1/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-06-20"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void publicReconcileRejectsDateOlderThanAllowedWindow() throws Exception {
        MockMvc publicMockMvc = mockMvc(new StaleGameReconciliationController(realService(new CountingLiveGameSyncService())));

        publicMockMvc.perform(post("/api/v1/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-06-11"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void publicReconcileDeduplicatesDuplicateDates() throws Exception {
        CountingLiveGameSyncService liveSyncService = new CountingLiveGameSyncService();
        MockMvc publicMockMvc = mockMvc(new StaleGameReconciliationController(realService(liveSyncService)));

        publicMockMvc.perform(post("/api/v1/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-06-19","2026-06-19"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates.length()").value(1))
                .andExpect(jsonPath("$.processedDateCount").value(1));

        assertThat(liveSyncService.syncCount).hasValue(1);
    }

    @Test
    void adminReconcileRejectsMissingOrWrongAdminKey() throws Exception {
        MockMvc adminMockMvc = adminMockMvc(new StaleGameReconciliationController(realService(new CountingLiveGameSyncService())));

        adminMockMvc.perform(post("/admin/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-04-16"]}
                                """))
                .andExpect(status().isUnauthorized());

        adminMockMvc.perform(post("/admin/games/reconcile-stale")
                        .header(AdminApiKeyFilter.ADMIN_KEY_HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-04-16"]}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminReconcileRunsWithValidAdminKey() throws Exception {
        CountingLiveGameSyncService liveSyncService = new CountingLiveGameSyncService();
        MockMvc adminMockMvc = adminMockMvc(new StaleGameReconciliationController(realService(liveSyncService)));

        adminMockMvc.perform(post("/admin/games/reconcile-stale")
                        .header(AdminApiKeyFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-04-16"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedDateCount").value(1));

        assertThat(liveSyncService.syncCount).hasValue(1);
    }

    @Test
    void concurrentReconcileRequestsForSameDateSkipSecondRequestWhileFirstIsRunning() throws Exception {
        BlockingLiveGameSyncService liveSyncService = new BlockingLiveGameSyncService();
        StaleGameReconciliationService reconciliationService = realService(liveSyncService);
        LocalDate date = LocalDate.of(2026, 5, 8);
        var executor = Executors.newFixedThreadPool(1);

        try {
            Future<StaleGameReconciliationResult> first = executor.submit(() -> reconciliationService.reconcile(List.of(date)));
            assertThat(liveSyncService.started.await(1, TimeUnit.SECONDS)).isTrue();

            StaleGameReconciliationResult second = reconciliationService.reconcile(List.of(date));

            assertThat(second.processedDateCount()).isZero();
            assertThat(second.skippedAlreadyInProgressCount()).isEqualTo(1);
            assertThat(second.dateResults()).extracting(StaleGameReconciliationDateResult::status)
                    .containsExactly(StaleGameReconciliationDateStatus.SKIPPED_ALREADY_IN_PROGRESS);

            liveSyncService.release.countDown();
            StaleGameReconciliationResult firstResult = first.get(1, TimeUnit.SECONDS);

            assertThat(firstResult.processedDateCount()).isEqualTo(1);
            assertThat(firstResult.skippedAlreadyInProgressCount()).isZero();
            assertThat(liveSyncService.syncCount).hasValue(1);
        } finally {
            liveSyncService.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void reconcileStaleGamesDoesNotExposeEventKeysOrInternalErrors() {
        StaleGameReconciliationService reconciliationService = realService(new FailingSummaryLiveGameSyncService());

        StaleGameReconciliationResult result = reconciliationService.reconcile(List.of(LocalDate.of(2026, 5, 8)));

        assertThat(result.dateResults()).hasSize(1);
        assertThat(result.dateResults().get(0).summary().events()).isEmpty();
        assertThat(result.dateResults().get(0).summary().errors()).containsExactly("refresh failed");
    }

    @Test
    void publicReconcileRequiresAdminKeyWhenFilterIsApplied() throws Exception {
        MockMvc protectedMockMvc = protectedMockMvc(new StaleGameReconciliationController(realService(new CountingLiveGameSyncService())));

        protectedMockMvc.perform(post("/api/v1/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-06-19"]}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicReconcileRejectsPayloadLargerThan32Kb() throws Exception {
        MockMvc protectedMockMvc = protectedMockMvc(new StaleGameReconciliationController(realService(new CountingLiveGameSyncService())));

        protectedMockMvc.perform(post("/api/v1/games/reconcile-stale")
                        .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dates\":[\"2026-06-19\"],\"padding\":\"" + "a".repeat((32 * 1024) + 1) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
    }

    private static final class RecordingStaleGameReconciliationService extends StaleGameReconciliationService {
        private List<LocalDate> requestedDates = List.of();

        private RecordingStaleGameReconciliationService() {
            super(new CountingLiveGameSyncService(), new KboReconcileProperties(), FIXED_CLOCK);
        }

        @Override
        public StaleGameReconciliationResult reconcilePublic(List<LocalDate> dates) {
            requestedDates = new ArrayList<>(dates);
            List<LocalDate> uniqueDates = dates.stream().distinct().sorted().toList();
            return new StaleGameReconciliationResult(
                    uniqueDates,
                    uniqueDates.size(),
                    0,
                    0,
                    uniqueDates.stream()
                            .map(date -> new StaleGameReconciliationDateResult(
                                    date,
                                    StaleGameReconciliationDateStatus.PROCESSED,
                                    null,
                                    new LiveSyncSummary(date, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of())
                            ))
                            .toList()
            );
        }

        @Override
        public StaleGameReconciliationResult reconcileAdmin(List<LocalDate> dates) {
            return reconcilePublic(dates);
        }
    }

    private MockMvc mockMvc(StaleGameReconciliationController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .modules(new JavaTimeModule())
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()
                ))
                .build();
    }

    private MockMvc adminMockMvc(StaleGameReconciliationController controller) {
        return protectedMockMvc(controller);
    }

    private MockMvc protectedMockMvc(StaleGameReconciliationController controller) {
        AppSecurityProperties legacyProperties = new AppSecurityProperties();
        KboAdminProperties adminProperties = new KboAdminProperties();
        adminProperties.setApiKey(ADMIN_KEY);
        legacyProperties.setRegistrationRequestMaxBytes(32 * 1024);
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(
                        new AdminApiKeyFilter(legacyProperties, adminProperties),
                        new RegistrationRequestSizeLimitFilter(legacyProperties)
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .modules(new JavaTimeModule())
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()
                ))
                .build();
    }

    private StaleGameReconciliationService realService(com.kbo.crawlerapi.service.LiveGameSyncService liveSyncService) {
        KboReconcileProperties properties = new KboReconcileProperties();
        properties.setPublicMaxDates(3);
        properties.setPublicAllowedPastDays(7);
        properties.setPublicCooldown(java.time.Duration.ofMinutes(3));
        properties.setAdminMaxDates(31);
        return new StaleGameReconciliationService(liveSyncService, properties, FIXED_CLOCK);
    }

    private static final class CountingLiveGameSyncService extends com.kbo.crawlerapi.service.LiveGameSyncService {
        private final AtomicInteger syncCount = new AtomicInteger();

        private CountingLiveGameSyncService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public LiveSyncSummary sync(LocalDate date, boolean force) {
            syncCount.incrementAndGet();
            return new LiveSyncSummary(date, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        }
    }

    private static final class BlockingLiveGameSyncService extends com.kbo.crawlerapi.service.LiveGameSyncService {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger syncCount = new AtomicInteger();

        private BlockingLiveGameSyncService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public LiveSyncSummary sync(LocalDate date, boolean force) {
            syncCount.incrementAndGet();
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new LiveSyncSummary(date, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        }
    }

    private static final class FailingSummaryLiveGameSyncService extends com.kbo.crawlerapi.service.LiveGameSyncService {

        private FailingSummaryLiveGameSyncService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public LiveSyncSummary sync(LocalDate date, boolean force) {
            return new LiveSyncSummary(
                    date,
                    1,
                    1,
                    0,
                    1,
                    0,
                    0,
                    1,
                    List.of(),
                    List.of("internal-event-key"),
                    List.of("internal stack or upstream detail")
            );
        }
    }
}
