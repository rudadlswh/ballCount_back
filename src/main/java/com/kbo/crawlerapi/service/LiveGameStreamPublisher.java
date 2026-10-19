package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.GameStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LiveGameStreamPublisher {

    private static final Logger log = LoggerFactory.getLogger(LiveGameStreamPublisher.class);

    private final GameReadService gameReadService;
    private final LiveGameStreamRegistry streamRegistry;

    public LiveGameStreamPublisher(GameReadService gameReadService, LiveGameStreamRegistry streamRegistry) {
        this.gameReadService = gameReadService;
        this.streamRegistry = streamRegistry;
    }

    public void publishAfterCommit(String publicGameId, boolean snapshotCreated, boolean statusChanged, GameStatus status) {
        String normalizedPublicGameId = LiveGameStreamRegistry.normalizePublicGameId(publicGameId);
        Runnable publish = () -> {
            int subscribers = streamRegistry.subscriberCount(normalizedPublicGameId);
            log.info(
                    "[SseStream] afterCommit publish start publicGameId={} snapshotCreated={} statusChanged={} subscribers={}",
                    normalizedPublicGameId,
                    snapshotCreated,
                    statusChanged,
                    subscribers
            );
            if (subscribers == 0) {
                log.warn(
                        "[SseStream] afterCommit publish subscribers=0 publicGameId={} normalizedPublicGameId={}",
                        publicGameId,
                        normalizedPublicGameId
                );
            }
            try {
                var snapshot = gameReadService.getGameLiveState(normalizedPublicGameId);
                if (statusChanged) {
                    streamRegistry.publishStatusChanged(normalizedPublicGameId, snapshot);
                }
                streamRegistry.publishSnapshot(normalizedPublicGameId, snapshot);
                if (isTerminal(status)) {
                    streamRegistry.complete(normalizedPublicGameId);
                }
                log.info("[SseStream] afterCommit publish end publicGameId={}", normalizedPublicGameId);
            } catch (RuntimeException exception) {
                log.warn(
                        "[SseStream] afterCommit publish failed publicGameId={} reason={}",
                        normalizedPublicGameId,
                        exception.getMessage()
                );
                throw exception;
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private boolean isTerminal(GameStatus status) {
        return status == GameStatus.FINAL || status == GameStatus.CANCELLED || status == GameStatus.POSTPONED;
    }
}
