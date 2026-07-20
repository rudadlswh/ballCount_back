package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.FcmProperties;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FcmServiceBeanTest {

    @Test
    void springCanInstantiateProductionConstructorsWhenTestConstructorsExist() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.registerBean(FcmProperties.class);
                context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
                context.registerBean(Clock.class, Clock::systemUTC);
                context.registerBean(FcmAccessTokenProvider.class);
                context.registerBean(FcmPushService.class);
                context.refresh();
                context.getBean(FcmPushService.class);
            }
        }).doesNotThrowAnyException();
    }
}
