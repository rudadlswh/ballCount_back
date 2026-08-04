package com.kbo.crawlerapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.kbo.crawlerapi.config.AppSecurityProperties;
import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.config.KboAdminProperties;
import com.kbo.crawlerapi.config.KboHttpProperties;
import com.kbo.crawlerapi.config.FcmProperties;
import com.kbo.crawlerapi.config.KboReconcileProperties;
import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.config.SyncProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        SyncProperties.class,
        LiveSyncProperties.class,
        ApnsProperties.class,
        AppSecurityProperties.class,
        KboAdminProperties.class,
        KboReconcileProperties.class,
        KboHttpProperties.class,
        FcmProperties.class
})
public class KboCrawlerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KboCrawlerApiApplication.class, args);
    }
}
