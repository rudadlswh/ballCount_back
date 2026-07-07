package com.kbo.crawlerapi.api;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.LiveGameStreamRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class GameLiveStreamController {

    private static final Logger log = LoggerFactory.getLogger(GameLiveStreamController.class);
    private static final int MAX_PUBLIC_GAME_ID_LENGTH = 100;

    private final GameRepository gameRepository;
    private final LiveGameStreamRegistry streamRegistry;

    public GameLiveStreamController(GameRepository gameRepository, LiveGameStreamRegistry streamRegistry) {
        this.gameRepository = gameRepository;
        this.streamRegistry = streamRegistry;
    }

    @GetMapping(path = "/games/{gameId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGame(@PathVariable String gameId) {
        validateGameId(gameId);
        String publicGameId = resolvePublicGameId(gameId);
        log.info("[SseStream] stream connected: requestedIdentity={} resolvedPublicGameId={}", gameId, publicGameId);
        return streamRegistry.subscribe(publicGameId);
    }

    private String resolvePublicGameId(String gameId) {
        Optional<Game> game = gameRepository.findByPublicGameId(gameId)
                .or(() -> gameRepository.findByProviderAndProviderGameId("kbo", gameId))
                .or(() -> gameRepository.findByProviderGameId(gameId));
        return game.map(Game::getPublicGameId).orElse(gameId);
    }

    private void validateGameId(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw new InvalidParameterException("gameId is required");
        }
        if (gameId.length() > MAX_PUBLIC_GAME_ID_LENGTH) {
            throw new InvalidParameterException("gameId is too long");
        }
    }
}
