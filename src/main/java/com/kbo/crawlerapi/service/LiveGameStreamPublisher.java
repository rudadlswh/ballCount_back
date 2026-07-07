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
        Runnable publish = () -> {
            log.info(
                    "[SseStream] afterCommit publish start publicGameId={} snapshotCreated={} statusChanged={} subscribers={}",
                    publicGameId,
                    snapshotCreated,
                    statusChanged,
                    streamRegistry.subscriberCount(publicGameId)
            );
            try {
                var snapshot = gameReadService.getGameLiveState(publicGameId);
                if (statusChanged) {
                    streamRegistry.publishStatusChanged(publicGameId, snapshot);
                }
                streamRegistry.publishSnapshot(publicGameId, snapshot);
                if (isTerminal(status)) {
                    streamRegistry.complete(publicGameId);
                }
                log.info("[SseStream] afterCommit publish end publicGameId={}", publicGameId);
            } catch (RuntimeException exception) {
                log.warn(
                        "[SseStream] afterCommit publish failed publicGameId={} reason={}",
                        publicGameId,
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
