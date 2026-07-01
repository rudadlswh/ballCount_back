package com.kbo.crawlerapi.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RegistrationInputNormalizerTest {

    @Test
    void clientEnvironmentOnlyAcceptsSandboxAndProduction() {
        assertThat(RegistrationInputNormalizer.normalizeClientEnvironment(null)).isEqualTo("sandbox");
        assertThat(RegistrationInputNormalizer.normalizeClientEnvironment(" production ")).isEqualTo("production");

        assertThatThrownBy(() -> RegistrationInputNormalizer.normalizeClientEnvironment("release"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("environment must be sandbox or production");
    }

    @Test
    void apnsEnvironmentKeepsExistingAliases() {
        assertThat(RegistrationInputNormalizer.normalizeApnsEnvironment(null)).isEqualTo("sandbox");
        assertThat(RegistrationInputNormalizer.normalizeApnsEnvironment("debug")).isEqualTo("sandbox");
        assertThat(RegistrationInputNormalizer.normalizeApnsEnvironment("development")).isEqualTo("sandbox");
        assertThat(RegistrationInputNormalizer.normalizeApnsEnvironment("release")).isEqualTo("production");
        assertThat(RegistrationInputNormalizer.normalizeApnsEnvironment("custom")).isEqualTo("custom");
    }

    @Test
    void normalizesFavoriteTeamAndTextValues() {
        assertThat(RegistrationInputNormalizer.requireText(" token ", "token", 10)).isEqualTo("token");
        assertThat(RegistrationInputNormalizer.optionalText("  ", "value", 10)).isNull();
        assertThat(RegistrationInputNormalizer.normalizeFavoriteTeamId(" LG ", 30)).isEqualTo("lg");

        assertThatThrownBy(() -> RegistrationInputNormalizer.normalizeFavoriteTeamId("invalid-team", 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("favoriteTeamId is invalid");
    }

    @Test
    void tokenHelpersAndNormalizedEqualsAreStable() {
        assertThat(RegistrationInputNormalizer.tokenPrefix("123456789")).isEqualTo("12345678");
        assertThat(RegistrationInputNormalizer.tokenFingerprint("token-123")).hasSize(12);
        assertThat(RegistrationInputNormalizer.normalizedEquals(" LG ", "lg")).isTrue();
        assertThat(RegistrationInputNormalizer.normalizedEquals(" ", "lg")).isFalse();
    }
}
