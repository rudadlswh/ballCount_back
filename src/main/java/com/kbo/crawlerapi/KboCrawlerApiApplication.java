package com.kbo.crawlerapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.kbo.crawlerapi.config.SchedulerShellProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SchedulerShellProperties.class)
public class KboCrawlerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KboCrawlerApiApplication.class, args);
    }
}
