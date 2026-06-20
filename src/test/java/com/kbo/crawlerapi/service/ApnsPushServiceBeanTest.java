package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.config.KboHttpProperties;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ApnsPushServiceBeanTest {

    @Test
    void springCanInstantiateProductionConstructorWhenTestConstructorExists() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.registerBean(ApnsProperties.class);
                context.registerBean(KboHttpProperties.class);
                context.registerBean(Clock.class, Clock::systemUTC);
                context.registerBean(ApnsPushService.class);
                context.refresh();
                context.getBean(ApnsPushService.class);
            }
        }).doesNotThrowAnyException();
    }
}
