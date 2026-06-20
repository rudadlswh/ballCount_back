package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class KboHttpPropertiesTest {

    @Test
    void bindsHttpTimeoutProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("kbo.http.connect-timeout", "4s")
                .withProperty("kbo.http.read-timeout", "9s")
                .withProperty("kbo.http.request-timeout", "11s");

        KboHttpProperties properties = Binder.get(environment)
                .bind("kbo.http", KboHttpProperties.class)
                .orElseThrow(IllegalStateException::new);

        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(11));
    }

    @Test
    void createsHttpClientsWithConfiguredTimeouts() {
        KboHttpProperties properties = new KboHttpProperties();
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(9));
        properties.setRequestTimeout(Duration.ofSeconds(12));

        assertThat(KboHttpClientFactory.apnsHttpClient(properties).connectTimeout())
                .contains(Duration.ofSeconds(5));
        assertThat(KboHttpClientFactory.requestTimeout(properties)).isEqualTo(Duration.ofSeconds(12));
        assertThat(KboHttpClientFactory.restClientRequestFactory(properties)).isNotNull();
    }
}
