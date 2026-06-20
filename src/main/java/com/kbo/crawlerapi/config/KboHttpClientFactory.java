package com.kbo.crawlerapi.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

public final class KboHttpClientFactory {

    private KboHttpClientFactory() {
    }

    public static JdkClientHttpRequestFactory restClientRequestFactory(KboHttpProperties properties) {
        KboHttpProperties effectiveProperties = properties == null ? new KboHttpProperties() : properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(positiveOrDefault(effectiveProperties.getConnectTimeout(), Duration.ofSeconds(3)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(positiveOrDefault(effectiveProperties.getReadTimeout(), Duration.ofSeconds(8)));
        return requestFactory;
    }

    public static HttpClient apnsHttpClient(KboHttpProperties properties) {
        KboHttpProperties effectiveProperties = properties == null ? new KboHttpProperties() : properties;
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(positiveOrDefault(effectiveProperties.getConnectTimeout(), Duration.ofSeconds(3)))
                .build();
    }

    public static Duration requestTimeout(KboHttpProperties properties) {
        KboHttpProperties effectiveProperties = properties == null ? new KboHttpProperties() : properties;
        return positiveOrDefault(effectiveProperties.getRequestTimeout(), Duration.ofSeconds(10));
    }

    private static Duration positiveOrDefault(Duration value, Duration defaultValue) {
        if (value == null || value.isZero() || value.isNegative()) {
            return defaultValue;
        }
        return value;
    }
}
