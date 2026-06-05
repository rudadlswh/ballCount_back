package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveActivityUpdateService {

    private static final Logger log = LoggerFactory.getLogger(LiveActivityUpdateService.class);

    private final LiveActivityTokenRepository liveActivityTokenRepository;
    private final LiveActivityContentStateBuilder contentStateBuilder;
    private final ApnsPushService apnsPushService;
    private final Clock applicationClock;

    public LiveActivityUpdateService(
            LiveActivityTokenRepository liveActivityTokenRepository,
            LiveActivityContentStateBuilder contentStateBuilder,
            ApnsPushService apnsPushService,
            Clock applicationClock
    ) {
        this.liveActivityTokenRepository = liveActivityTokenRepository;
        this.contentStateBuilder = contentStateBuilder;
        this.apnsPushService = apnsPushService;
        this.applicationClock = applicationClock;
    }

    @Transactional
    public LiveActivityDeliveryResult deliverUpdate(Game game, NotificationEvent event) {
        List<LiveActivityToken> matches = liveActivityTokenRepository.findByActiveTrue()
                .stream()
                .filter(token -> contentStateBuilder.matches(game, token))
                .toList();
        log.info(
                "[LiveActivity] token matched eventId={} publicGameId={} providerGameId={} databaseId={} matchedCount={}",
                event.getId(),
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getId(),
                matches.size()
        );
        if (matches.isEmpty()) {
            log.info("[LiveActivity] APNs skipped eventId={} reason=no_matching_live_activity_tokens", event.getId());
            return new LiveActivityDeliveryResult(0, 1, 0);
        }

        int sent = 0;
        int skipped = 0;
        int failed = 0;
        for (LiveActivityToken token : matches) {
            Map<String, Object> contentState = contentStateBuilder.build(game, token, event);
            ApnsPushService.ApnsSendResult result = apnsPushService.sendLiveActivityUpdate(event, token, contentState);
            if (result.sent()) {
                sent++;
            } else if (result.skipped()) {
                skipped++;
            } else {
                failed++;
                if (result.invalidToken()) {
                    token.disable(OffsetDateTime.now(applicationClock));
                }
            }
        }
        log.info("[LiveActivity] APNs result eventId={} sent={} skipped={} failed={}", event.getId(), sent, skipped, failed);
        return new LiveActivityDeliveryResult(sent, skipped, failed);
    }

    public record LiveActivityDeliveryResult(int sentCount, int skippedCount, int failedCount) {
    }
}
