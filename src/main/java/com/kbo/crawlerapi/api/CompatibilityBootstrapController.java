package com.kbo.crawlerapi.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapResponse;
import com.kbo.crawlerapi.service.CompatibilityBootstrapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
public class CompatibilityBootstrapController {

    private final CompatibilityBootstrapService compatibilityBootstrapService;

    public CompatibilityBootstrapController(CompatibilityBootstrapService compatibilityBootstrapService) {
        this.compatibilityBootstrapService = compatibilityBootstrapService;
    }

    @GetMapping("/v1/bootstrap")
    @Operation(
            summary = "Get iOS compatibility bootstrap payload",
            description = "Returns the compatibility bootstrap payload consumed by the current iOS app."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Compatibility bootstrap payload",
                    content = @Content(schema = @Schema(implementation = CompatibilityBootstrapResponse.class))
            )
    })
    public CompatibilityBootstrapResponse getBootstrap() {
        return compatibilityBootstrapService.getBootstrap();
    }
}
