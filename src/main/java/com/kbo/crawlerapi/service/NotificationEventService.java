package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import com.kbo.crawlerapi.repository.NotificationEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationEventService {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventService.class);
    public static final String EVENT_GAME_START = "GAME_START";
    public static final String EVENT_SCORE_CHANGED = "SCORE_CHANGED";
    public static final String EVENT_LEAD_CHANGED = "LEAD_CHANGED";
    public static final String EVENT_GAME_END = "GAME_END";
    public static final String EVENT_GAME_CANCELLED = "GAME_CANCELLED";
    public static final String EVENT_CANCELLED = "CANCELLED";
    public static final String EVENT_POSTPONED = "POSTPONED";
    public static final String EVENT_GAME_DELAYED = "GAME_DELAYED";
    public static final String EVENT_GAME_SUSPENDED = "GAME_SUSPENDED";
    public static final String EVENT_GAME_INTERRUPTED = "GAME_INTERRUPTED";
    public static final String EVENT_GAME_RESUME_SCHEDULED = "GAME_RESUME_SCHEDULED";
    public static final String EVENT_GAME_RESUMED = "GAME_RESUMED";
    public static final String EVENT_ON_BASE = "ON_BASE";
    public static final String EVENT_INNING_CHANGED = "INNING_CHANGED";
    public static final String PAYLOAD_EVENT_TEAM_ID = "eventTeamId";
    private static final ZoneId KBO_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private final NotificationEventRepository notificationEventRepository;
    private final NotificationDeviceRepository notificationDeviceRepository;
    private final ApnsPushService apnsPushService;
    private final FcmPushService fcmPushService;
    private final LiveActivityUpdateService liveActivityUpdateService;
    private final ObjectMapper objectMapper;
    private final Clock applicationClock;
    private final TransactionTemplate transactionTemplate;
    private final Executor notificationDeliveryExecutor;

    public NotificationEventService(
            NotificationEventRepository notificationEventRepository,
            NotificationDeviceRepository notificationDeviceRepository,
            ApnsPushService apnsPushService,
            ObjectMapper objectMapper,
            Clock applicationClock
    ) {
        this(notificationEventRepository, notificationDeviceRepository, apnsPushService, null, null, objectMapper, applicationClock, null, Runnable::run);
    }

    @Autowired
    public NotificationEventService(
            NotificationEventRepository notificationEventRepository,
            NotificationDeviceRepository notificationDeviceRepository,
            ApnsPushService apnsPushService,
            LiveActivityUpdateService liveActivityUpdateService,
            FcmPushService fcmPushService,
            ObjectMapper objectMapper,
            Clock applicationClock,
            PlatformTransactionManager transactionManager,
            @Qualifier("notificationDeliveryExecutor") Executor notificationDeliveryExecutor
    ) {
        this.notificationEventRepository = notificationEventRepository;
        this.notificationDeviceRepository = notificationDeviceRepository;
        this.apnsPushService = apnsPushService;
        this.fcmPushService = fcmPushService;
        this.liveActivityUpdateService = liveActivityUpdateService;
        this.objectMapper = objectMapper;
        this.applicationClock = applicationClock;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.notificationDeliveryExecutor = notificationDeliveryExecutor == null ? Runnable::run : notificationDeliveryExecutor;
    }

    public NotificationEventService(
            NotificationEventRepository notificationEventRepository,
            NotificationDeviceRepository notificationDeviceRepository,
            ApnsPushService apnsPushService,
            ObjectMapper objectMapper,
            Clock applicationClock,
            PlatformTransactionManager transactionManager
    ) {
        this(notificationEventRepository, notificationDeviceRepository, apnsPushService, null, null, objectMapper, applicationClock, transactionManager, Runnable::run);
    }

    public NotificationEventService(
            NotificationEventRepository notificationEventRepository,
            NotificationDeviceRepository notificationDeviceRepository,
            ApnsPushService apnsPushService,
            LiveActivityUpdateService liveActivityUpdateService,
            ObjectMapper objectMapper,
            Clock applicationClock
    ) {
        this(notificationEventRepository, notificationDeviceRepository, apnsPushService, liveActivityUpdateService, null, objectMapper, applicationClock, null, Runnable::run);
    }

    public EventDeliveryResult createAndDeliver(Game game, NotificationEventDraft draft) {
        if (!isDeliverableEventType(draft.eventType())) {
            return EventDeliveryResult.skipped(draft.eventKey());
        }
        String initialApnsSkipReason = apnsPushService.readinessSkipReason();
        String initialFcmSkipReason = fcmReadinessSkipReason();
        if (initialApnsSkipReason != null && initialFcmSkipReason != null) {
            deliverLiveActivityEnd(game, draft.eventType());
            log.warn(
                    "[Notifications] delivery skipped before event persistence eventKey={} eventType={} reason={} configuredEnv={}",
                    draft.eventKey(),
                    draft.eventType(),
                    initialApnsSkipReason + "," + initialFcmSkipReason,
                    apnsPushService.configuredEnvironment()
            );
            return EventDeliveryResult.skipped(draft.eventKey());
        }
        PreparedDelivery prepared = inTransaction(() -> prepareDelivery(game, draft, false));
        if (prepared.duplicated()) {
            return EventDeliveryResult.duplicate(draft.eventKey());
        }
        NotificationEvent event = prepared.event();
        List<String> eventTeamIds = prepared.eventTeamIds();
        List<NotificationDevice> relevantTeamDevices = prepared.relevantTeamDevices();
        deliverLiveActivityEnd(game, draft.eventType());

        return deliverPrepared(game, draft, event, eventTeamIds, relevantTeamDevices);
    }

    public EventDeliveryResult createAndDeliverAsync(Game game, NotificationEventDraft draft) {
        if (notificationEventRepository == null || notificationDeviceRepository == null || apnsPushService == null) {
            return createAndDeliver(game, draft);
        }
        if (!isDeliverableEventType(draft.eventType())) {
            return EventDeliveryResult.skipped(draft.eventKey());
        }
        String initialApnsSkipReason = apnsPushService.readinessSkipReason();
        String initialFcmSkipReason = fcmReadinessSkipReason();
        if (initialApnsSkipReason != null && initialFcmSkipReason != null) {
            deliverLiveActivityEnd(game, draft.eventType());
            log.warn(
                    "[Notifications] delivery skipped before event persistence eventKey={} eventType={} reason={} configuredEnv={}",
                    draft.eventKey(),
                    draft.eventType(),
                    initialApnsSkipReason + "," + initialFcmSkipReason,
                    apnsPushService.configuredEnvironment()
            );
            return EventDeliveryResult.skipped(draft.eventKey());
        }
        PreparedDelivery prepared = inTransaction(() -> prepareDelivery(game, draft, true));
        if (prepared.duplicated()) {
            return EventDeliveryResult.duplicate(draft.eventKey());
        }
        NotificationEvent event = prepared.event();
        List<NotificationDevice> relevantTeamDevices = prepared.relevantTeamDevices();
        deliverLiveActivityEnd(game, draft.eventType());
        if (relevantTeamDevices.isEmpty() && initialFcmSkipReason != null) {
            markEventDelivery(event, "skipped", ApnsPushService.NO_RELEVANT_DEVICES);
            return new EventDeliveryResult(event.getId(), draft.eventKey(), true, 0, 1, 0);
        }
        List<NotificationDevice> deliverableDevices = deliverableDevices(relevantTeamDevices, draft, game);
        String deviceSkipReason = deviceReadinessSkipReason(relevantTeamDevices, deliverableDevices, game);
        if (deviceSkipReason != null && initialFcmSkipReason != null) {
            markEventDelivery(event, "skipped", deviceSkipReason);
            return new EventDeliveryResult(event.getId(), draft.eventKey(), true, 0, relevantTeamDevices.size(), 0);
        }
        markEventDelivery(event, "queued", null);
        log.info(
                "[Notifications] APNs queued eventId={} eventKey={} eventType={} deliverableDeviceCount={} deliverableDeviceEnvCounts={}",
                event.getId(),
                draft.eventKey(),
                draft.eventType(),
                deliverableDevices.size(),
                environmentCounts(deliverableDevices)
        );
        notificationDeliveryExecutor.execute(() -> deliverPrepared(game, draft, event, prepared.eventTeamIds(), relevantTeamDevices));
        return new EventDeliveryResult(event.getId(), draft.eventKey(), true, 0, 0, 0);
    }

    private EventDeliveryResult deliverPrepared(
            Game game,
            NotificationEventDraft draft,
            NotificationEvent event,
            List<String> eventTeamIds,
            List<NotificationDevice> relevantTeamDevices
    ) {
        ProviderDelivery fcmDelivery = deliverFcm(event, draft, game, eventTeamIds);
        ApnsPushService.ApnsDiagnostics diagnostics = apnsPushService.diagnostics();
        log.info(
                "[Notifications] delivery diagnostics eventId={} eventKey={} pushEnabled={} configTeamIdPresent={} configKeyIdPresent={} configBundleIdPresent={} privateKeyPathPresent={} inlinePrivateKeyPresent={} configuredEnv={} eventTeamIds={} relevantDeviceCount={} relevantDeviceEnvCounts={}",
                event.getId(),
                draft.eventKey(),
                diagnostics.pushEnabled(),
                diagnostics.teamIdPresent(),
                diagnostics.keyIdPresent(),
                diagnostics.bundleIdPresent(),
                diagnostics.privateKeyPathPresent(),
                diagnostics.inlinePrivateKeyPresent(),
                diagnostics.configuredEnvironment(),
                eventTeamIds,
                relevantTeamDevices.size(),
                environmentCounts(relevantTeamDevices)
        );

        if (relevantTeamDevices.isEmpty()) {
            return finishDelivery(
                    event,
                    draft,
                    fcmDelivery,
                    ProviderDelivery.none(ApnsPushService.NO_RELEVANT_DEVICES)
            );
        }

        relevantTeamDevices.forEach(device -> logDeviceDiagnostics(event, device, game));

        List<NotificationDevice> deliverableDevices = deliverableDevices(relevantTeamDevices, draft, game);
        String deviceSkipReason = deviceReadinessSkipReason(relevantTeamDevices, deliverableDevices, game);
        if (deviceSkipReason != null) {
            return finishDelivery(event, draft, fcmDelivery, ProviderDelivery.skipped(relevantTeamDevices.size(), deviceSkipReason));
        }

        String apnsSkipReason = apnsPushService.readinessSkipReason();
        if (apnsSkipReason != null) {
            return finishDelivery(event, draft, fcmDelivery, ProviderDelivery.skipped(deliverableDevices.size(), apnsSkipReason));
        }

        int sent = 0;
        int skipped = 0;
        int failed = 0;
        String lastFailure = null;
        List<NotificationDevice> invalidDevices = new java.util.ArrayList<>();
        Instant apnsSendRequestedAt = Instant.now(applicationClock);
        log.info(
                "[Notifications] APNs send requested at={} eventId={} eventKey={} eventType={} deliverableDeviceCount={} deliverableDeviceEnvCounts={}",
                apnsSendRequestedAt,
                event.getId(),
                draft.eventKey(),
                draft.eventType(),
                deliverableDevices.size(),
                environmentCounts(deliverableDevices)
        );
        for (NotificationDevice device : deliverableDevices) {
            ApnsPushService.ApnsSendResult result = apnsPushService.send(event, device);
            if (result.sent()) {
                sent++;
            } else if (result.skipped()) {
                skipped++;
                lastFailure = result.reason();
            } else {
                failed++;
                lastFailure = result.reason();
                if (result.invalidToken()) {
                    invalidDevices.add(device);
                }
            }
        }

        ProviderDelivery apnsDelivery = new ProviderDelivery(sent, skipped, failed, lastFailure, invalidDevices);
        EventDeliveryResult combinedResult = finishDelivery(event, draft, fcmDelivery, apnsDelivery);
        Instant apnsResultAt = Instant.now(applicationClock);
        log.info(
                "[Notifications] APNs result at={} sent_at={} eventId={} eventKey={} eventType={} gameScheduledAt={} status={} sent={} skipped={} failed={} deliverableDeviceEnvCounts={} durationMs={}",
                apnsResultAt,
                apnsResultAt,
                event.getId(),
                draft.eventKey(),
                draft.eventType(),
                game.getScheduledAt(),
                deliveryStatus(apnsDelivery),
                sent,
                skipped,
                failed,
                environmentCounts(deliverableDevices),
                Math.max(0, Duration.between(apnsSendRequestedAt, apnsResultAt).toMillis())
        );
        return combinedResult;
    }

    private ProviderDelivery deliverFcm(
            NotificationEvent event,
            NotificationEventDraft draft,
            Game game,
            List<String> eventTeamIds
    ) {
        if (fcmPushService == null || fcmPushService.readinessSkipReason() != null) {
            return ProviderDelivery.none(fcmReadinessSkipReason());
        }
        List<NotificationDevice> candidates = notificationDeviceRepository.findAndroidDeliveryTargets(
                fcmPushService.configuredEnvironment(),
                eventTeamIds.stream().map(value -> value.toLowerCase(java.util.Locale.ROOT)).toList(),
                game.getPublicGameId()
        );
        if (candidates == null || candidates.isEmpty()) {
            return ProviderDelivery.none(ApnsPushService.NO_RELEVANT_DEVICES);
        }
        List<NotificationDevice> deliverable = candidates.stream()
                .filter(NotificationDevice::isNotificationsEnabled)
                .filter(device -> isMonitoredGame(device, game) || favoriteTeamGameSkipReason(device, game) == null)
                .filter(device -> isMonitoredGame(device, game) || eventSettingEnabled(device, draft.eventType()))
                .filter(device -> isMonitoredGame(device, game) || favoriteTeamOnlyAllows(device, draft))
                .filter(device -> isMonitoredGame(device, game) || muteWhenLosingAllows(device, draft.eventType(), game))
                .filter(device -> isMonitoredGame(device, game) || quietHoursAllow(device))
                .toList();
        if (deliverable.isEmpty()) {
            return ProviderDelivery.skipped(candidates.size(), ApnsPushService.DEVICE_NOTIFICATION_SETTINGS_DISABLED);
        }

        int sent = 0;
        int skipped = 0;
        int failed = 0;
        String lastFailure = null;
        List<NotificationDevice> invalidDevices = new java.util.ArrayList<>();
        for (NotificationDevice device : deliverable) {
            FcmPushService.FcmSendResult result = fcmPushService.send(event, device);
            if (result.sent()) {
                sent++;
            } else if (result.skipped()) {
                skipped++;
                lastFailure = result.reason();
            } else {
                failed++;
                lastFailure = result.reason();
                if (result.invalidToken()) {
                    invalidDevices.add(device);
                }
            }
        }
        log.info(
                "[FCM] result eventId={} eventKey={} sent={} skipped={} failed={}",
                event.getId(),
                draft.eventKey(),
                sent,
                skipped,
                failed
        );
        return new ProviderDelivery(sent, skipped, failed, lastFailure, invalidDevices);
    }

    private EventDeliveryResult finishDelivery(
            NotificationEvent event,
            NotificationEventDraft draft,
            ProviderDelivery first,
            ProviderDelivery second
    ) {
        int sent = first.sent() + second.sent();
        int skipped = first.skipped() + second.skipped();
        int failed = first.failed() + second.failed();
        String lastFailure = sent > 0 && failed == 0
                ? null
                : second.lastFailure() != null ? second.lastFailure() : first.lastFailure();
        List<NotificationDevice> invalidDevices = new java.util.ArrayList<>(first.invalidDevices());
        invalidDevices.addAll(second.invalidDevices());
        String status = sent > 0 && failed == 0
                ? "sent"
                : sent > 0
                ? "partial_failed"
                : failed > 0
                ? "failed"
                : "skipped";
        recordDeliveryResult(event, status, lastFailure, invalidDevices);
        return new EventDeliveryResult(event.getId(), draft.eventKey(), true, sent, skipped, failed);
    }

    private String deliveryStatus(ProviderDelivery delivery) {
        if (delivery.sent() > 0 && delivery.failed() == 0) {
            return "sent";
        }
        if (delivery.sent() > 0) {
            return "partial_failed";
        }
        return delivery.failed() > 0 ? "failed" : "skipped";
    }

    private String fcmReadinessSkipReason() {
        return fcmPushService == null ? FcmPushService.PUSH_DISABLED : fcmPushService.readinessSkipReason();
    }

    private void deliverLiveActivityEnd(Game game, String eventType) {
        if (liveActivityUpdateService == null || !isLiveActivityEndEventType(eventType)) {
            return;
        }
        try {
            liveActivityUpdateService.deliverEnd(game, eventType);
        } catch (Exception exception) {
            log.warn(
                    "[LiveActivityEnd] delivery failed publicGameId={} eventType={} reason={}",
                    game.getPublicGameId(),
                    eventType,
                    exception.getMessage()
            );
        }
    }

    public boolean eventExists(String eventKey) {
        return notificationEventRepository != null && notificationEventRepository.existsByEventKey(eventKey);
    }

    public boolean eventExists(NotificationEventDraft draft) {
        return draft != null
                && (eventExists(draft.eventKey()) || legacyEventKeys(draft).stream().anyMatch(this::eventExists));
    }

    public long countByGameId(UUID gameId) {
        return notificationEventRepository == null || gameId == null ? 0 : notificationEventRepository.countByGame_Id(gameId);
    }

    public NotificationEvent transientEvent(Game game, NotificationEventDraft draft) {
        return new NotificationEvent(
                UUID.randomUUID(),
                game,
                draft.eventType(),
                draft.eventKey(),
                draft.title(),
                draft.body(),
                toJson(draft.payload())
        );
    }

    public String targetedDeviceSkipReason(NotificationDevice device, NotificationEventDraft draft, Game game) {
        if (!"ios".equalsIgnoreCase(device.getPlatform()) && !"android".equalsIgnoreCase(device.getPlatform())) {
            return ApnsPushService.UNSUPPORTED_PLATFORM;
        }
        if (!device.isNotificationsEnabled()) {
            return ApnsPushService.DEVICE_NOTIFICATIONS_DISABLED;
        }
        if (device.getDeviceToken() == null || device.getDeviceToken().isBlank()) {
            return ApnsPushService.APNS_BAD_DEVICE_TOKEN;
        }
        String favoriteTeamGameSkipReason = favoriteTeamGameSkipReason(device, game);
        if (favoriteTeamGameSkipReason != null) {
            return favoriteTeamGameSkipReason;
        }
        if (!eventSettingEnabled(device, draft.eventType())
                || !favoriteTeamOnlyAllows(device, draft)
                || !muteWhenLosingAllows(device, draft.eventType(), game)) {
            return ApnsPushService.DEVICE_NOTIFICATION_SETTINGS_DISABLED;
        }
        if (!quietHoursAllow(device)) {
            return ApnsPushService.DEVICE_NOTIFICATION_QUIET_HOURS;
        }
        return null;
    }

    private PreparedDelivery prepareDelivery(Game game, NotificationEventDraft draft, boolean optimizedTargetLookup) {
        if (notificationEventRepository.findByEventKey(draft.eventKey()).isPresent()
                || legacyEventKeys(draft).stream().anyMatch(notificationEventRepository::existsByEventKey)) {
            return PreparedDelivery.duplicateResult();
        }
        NotificationEvent event = notificationEventRepository.save(new NotificationEvent(
                UUID.randomUUID(),
                game,
                draft.eventType(),
                draft.eventKey(),
                draft.title(),
                draft.body(),
                toJson(draft.payload())
        ));
        log.info(
                "[Notifications] event persisted eventId={} eventKey={} eventType={} created_at={} gameScheduledAt={} detectedLiveStatusAt={}",
                event.getId(),
                draft.eventKey(),
                draft.eventType(),
                OffsetDateTime.now(applicationClock),
                game.getScheduledAt(),
                Instant.now(applicationClock)
        );
        List<String> eventTeamIds = eventTeamIds(game);
        List<NotificationDevice> relevantTeamDevices = optimizedTargetLookup
                ? notificationDeviceRepository.findDeliveryTargets(
                        "ios",
                        apnsPushService.configuredEnvironment(),
                        eventTeamIds.stream()
                                .map(teamId -> teamId.toLowerCase(java.util.Locale.ROOT))
                                .toList()
                )
                : notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue("ios");
        return new PreparedDelivery(false, event, eventTeamIds, relevantTeamDevices);
    }

    private void markEventDelivery(NotificationEvent event, String status, String errorMessage) {
        recordDeliveryResult(event, status, errorMessage, List.of());
    }

    private void recordDeliveryResult(NotificationEvent event, String status, String errorMessage, List<NotificationDevice> invalidDevices) {
        if (transactionTemplate == null) {
            event.markDelivery(status, OffsetDateTime.now(applicationClock), errorMessage);
            if (!invalidDevices.isEmpty()) {
                OffsetDateTime now = OffsetDateTime.now(applicationClock);
                invalidDevices.forEach(device -> device.disable(now));
            }
            return;
        }
        inTransaction(() -> {
            notificationEventRepository.findById(event.getId()).ifPresent(managedEvent ->
                    managedEvent.markDelivery(status, OffsetDateTime.now(applicationClock), errorMessage));
            if (!invalidDevices.isEmpty()) {
                OffsetDateTime now = OffsetDateTime.now(applicationClock);
                List<UUID> invalidDeviceIds = invalidDevices.stream()
                        .map(NotificationDevice::getId)
                        .toList();
                notificationDeviceRepository.findAllById(invalidDeviceIds).forEach(device -> device.disable(now));
            }
            return null;
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        return transactionTemplate.execute(status -> work.get());
    }

    private List<String> eventTeamIds(Game game) {
        Set<String> teamIds = new LinkedHashSet<>();
        teamIds.add(game.getAwayTeam().getTeamCode());
        teamIds.add(game.getHomeTeam().getTeamCode());
        return teamIds.stream().toList();
    }

    private boolean isRelevant(NotificationDevice device, Game game) {
        String favoriteTeamId = device.getFavoriteTeamId();
        return favoriteTeamId != null
                && (favoriteTeamId.equalsIgnoreCase(game.getHomeTeam().getTeamCode()) || favoriteTeamId.equalsIgnoreCase(game.getAwayTeam().getTeamCode()));
    }

    private List<NotificationDevice> deliverableDevices(List<NotificationDevice> devices, NotificationEventDraft draft, Game game) {
        return devices.stream()
                .filter(device -> "ios".equalsIgnoreCase(device.getPlatform()))
                .filter(NotificationDevice::isNotificationsEnabled)
                .filter(device -> favoriteTeamGameSkipReason(device, game) == null)
                .filter(device -> eventSettingEnabled(device, draft.eventType()))
                .filter(device -> favoriteTeamOnlyAllows(device, draft))
                .filter(device -> muteWhenLosingAllows(device, draft.eventType(), game))
                .filter(this::quietHoursAllow)
                .toList();
    }

    private String deviceReadinessSkipReason(List<NotificationDevice> devices, List<NotificationDevice> deliverableDevices, Game game) {
        if (!deliverableDevices.isEmpty()) {
            return null;
        }

        List<NotificationDevice> supportedDevices = devices.stream()
                .filter(device -> "ios".equalsIgnoreCase(device.getPlatform()))
                .toList();
        if (supportedDevices.isEmpty()) {
            return ApnsPushService.UNSUPPORTED_PLATFORM;
        }

        List<NotificationDevice> enabledDevices = supportedDevices.stream()
                .filter(NotificationDevice::isNotificationsEnabled)
                .toList();
        if (enabledDevices.isEmpty()) {
            return ApnsPushService.DEVICE_NOTIFICATIONS_DISABLED;
        }
        boolean anyFavoriteTeamGameMatch = enabledDevices.stream()
                .anyMatch(device -> favoriteTeamGameSkipReason(device, game) == null);
        if (!anyFavoriteTeamGameMatch) {
            return ApnsPushService.FAVORITE_TEAM_MISMATCH;
        }

        return ApnsPushService.DEVICE_NOTIFICATION_SETTINGS_DISABLED;
    }

    private Map<String, Long> environmentCounts(List<NotificationDevice> devices) {
        return devices.stream()
                .collect(Collectors.groupingBy(
                        device -> device.getEnvironment() == null || device.getEnvironment().isBlank()
                                ? "unknown"
                                : device.getEnvironment(),
                        java.util.TreeMap::new,
                        Collectors.counting()
                ));
    }

    private boolean eventSettingEnabled(NotificationDevice device, String eventType) {
        return switch (eventType) {
            case EVENT_GAME_START -> device.isGameStartEnabled();
            case EVENT_SCORE_CHANGED -> device.isScoreChangeEnabled();
            case EVENT_LEAD_CHANGED -> device.isLeadChangeEnabled();
            case EVENT_GAME_END -> device.isGameEndEnabled();
            case EVENT_GAME_CANCELLED -> device.isRainDelayEnabled();
            case EVENT_CANCELLED -> device.isRainDelayEnabled();
            case EVENT_POSTPONED -> device.isRainDelayEnabled();
            case EVENT_GAME_DELAYED -> device.isRainDelayEnabled();
            case EVENT_GAME_SUSPENDED -> device.isRainDelayEnabled();
            case EVENT_GAME_INTERRUPTED -> device.isRainDelayEnabled();
            case EVENT_GAME_RESUME_SCHEDULED -> device.isGameStartEnabled();
            case EVENT_GAME_RESUMED -> device.isGameStartEnabled();
            case EVENT_ON_BASE -> device.isOnBaseEnabled();
            case EVENT_INNING_CHANGED -> device.isInningChangeEnabled();
            default -> false;
        };
    }

    private boolean quietHoursAllow(NotificationDevice device) {
        if (!device.isQuietHoursEnabled()) {
            return true;
        }
        int currentHour = OffsetDateTime.now(applicationClock).atZoneSameInstant(KBO_TIME_ZONE).getHour();
        int startHour = device.getQuietHoursStartHour();
        int endHour = device.getQuietHoursEndHour();
        if (startHour == endHour) {
            return false;
        }
        boolean isQuiet = startHour < endHour
                ? currentHour >= startHour && currentHour < endHour
                : currentHour >= startHour || currentHour < endHour;
        return !isQuiet;
    }

    private String favoriteTeamGameSkipReason(NotificationDevice device, Game game) {
        if (!device.isFavoriteTeamOnlyEnabled()) {
            return null;
        }
        String favoriteTeamId = device.getFavoriteTeamId();
        if (favoriteTeamId != null && !favoriteTeamId.isBlank() && isRelevant(device, game)) {
            return null;
        }
        return ApnsPushService.FAVORITE_TEAM_MISMATCH;
    }

    private boolean favoriteTeamOnlyAllows(NotificationDevice device, NotificationEventDraft draft) {
        if (!device.isFavoriteTeamOnlyEnabled() || !isTeamScopedRealtimeEvent(draft.eventType())) {
            return true;
        }
        String eventTeamId = payloadText(draft, PAYLOAD_EVENT_TEAM_ID);
        return eventTeamId != null && eventTeamId.equalsIgnoreCase(device.getFavoriteTeamId());
    }

    private boolean isMonitoredGame(NotificationDevice device, Game game) {
        return device.getMonitoredGameId() != null
                && game != null
                && device.getMonitoredGameId().equals(game.getPublicGameId());
    }

    private boolean muteWhenLosingAllows(NotificationDevice device, String eventType, Game game) {
        if (!device.isMuteWhenLosingEnabled()
                || (!isTeamScopedRealtimeEvent(eventType) && !EVENT_INNING_CHANGED.equals(eventType))) {
            return true;
        }
        String favoriteTeamId = device.getFavoriteTeamId();
        if (favoriteTeamId == null || game.getHomeScore() == null || game.getAwayScore() == null) {
            return false;
        }
        if (favoriteTeamId.equals(game.getHomeTeam().getTeamCode())) {
            return game.getHomeScore() >= game.getAwayScore();
        }
        if (favoriteTeamId.equals(game.getAwayTeam().getTeamCode())) {
            return game.getAwayScore() >= game.getHomeScore();
        }
        return false;
    }

    private boolean isTeamScopedRealtimeEvent(String eventType) {
        return EVENT_SCORE_CHANGED.equals(eventType)
                || EVENT_ON_BASE.equals(eventType)
                || EVENT_LEAD_CHANGED.equals(eventType);
    }

    private String payloadText(NotificationEventDraft draft, String key) {
        Object value = draft.payload().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private List<String> legacyEventKeys(NotificationEventDraft draft) {
        Object value = draft.payload().get("legacyEventKeys");
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private void logDeviceDiagnostics(NotificationEvent event, NotificationDevice device, Game game) {
        boolean favoriteTeamMatches = isRelevant(device, game);
        String skipReason = favoriteTeamGameSkipReason(device, game);
        log.info(
                "[Notifications] device diagnostics eventId={} deviceId={} platform={} deviceEnv={} notificationsEnabled={} favoriteTeamId={} favoriteTeamOnlyEnabled={} gamePublicId={} homeTeamId={} awayTeamId={} favoriteTeamMatches={} skipReason={} configuredEnv={}",
                event.getId(),
                device.getId(),
                device.getPlatform(),
                device.getEnvironment(),
                device.isNotificationsEnabled(),
                device.getFavoriteTeamId(),
                device.isFavoriteTeamOnlyEnabled(),
                game.getPublicGameId(),
                game.getHomeTeam().getTeamCode(),
                game.getAwayTeam().getTeamCode(),
                favoriteTeamMatches,
                skipReason,
                apnsPushService.configuredEnvironment()
        );
    }

    private boolean isDeliverableEventType(String eventType) {
        return switch (eventType) {
            case EVENT_GAME_START, EVENT_SCORE_CHANGED, EVENT_LEAD_CHANGED, EVENT_GAME_END, EVENT_GAME_CANCELLED, EVENT_CANCELLED, EVENT_POSTPONED, EVENT_GAME_DELAYED, EVENT_GAME_SUSPENDED, EVENT_GAME_INTERRUPTED, EVENT_GAME_RESUME_SCHEDULED, EVENT_GAME_RESUMED, EVENT_ON_BASE, EVENT_INNING_CHANGED -> true;
            default -> false;
        };
    }

    private boolean isLiveActivityEndEventType(String eventType) {
        return EVENT_GAME_END.equals(eventType)
                || EVENT_GAME_CANCELLED.equals(eventType)
                || EVENT_CANCELLED.equals(eventType)
                || EVENT_POSTPONED.equals(eventType);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize notification payload", exception);
        }
    }

    public record NotificationEventDraft(
            String eventType,
            String eventKey,
            String title,
            String body,
            Map<String, Object> payload
    ) {
    }

    private record PreparedDelivery(
            boolean duplicated,
            NotificationEvent event,
            List<String> eventTeamIds,
            List<NotificationDevice> relevantTeamDevices
    ) {
        private static PreparedDelivery duplicateResult() {
            return new PreparedDelivery(true, null, List.of(), List.of());
        }
    }

    private record ProviderDelivery(
            int sent,
            int skipped,
            int failed,
            String lastFailure,
            List<NotificationDevice> invalidDevices
    ) {
        private static ProviderDelivery none(String reason) {
            return new ProviderDelivery(0, 0, 0, reason, List.of());
        }

        private static ProviderDelivery skipped(int count, String reason) {
            return new ProviderDelivery(0, count, 0, reason, List.of());
        }
    }

    public record EventDeliveryResult(
            UUID eventId,
            String eventKey,
            boolean eventCreated,
            int sentCount,
            int skippedCount,
            int failedCount
    ) {
        public static EventDeliveryResult duplicate(String eventKey) {
            return new EventDeliveryResult(null, eventKey, false, 0, 0, 0);
        }

        public static EventDeliveryResult skipped(String eventKey) {
            return new EventDeliveryResult(null, eventKey, false, 0, 1, 0);
        }
    }
}
