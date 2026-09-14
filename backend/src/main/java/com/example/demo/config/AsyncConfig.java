package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Ukljucuje pozadinsko izvrsavanje ({@code @Async}) i daje mu vlastiti bazen niti.
 *
 * Postoji zbog SBOM analize: Grype se izvrsava sekundama (i vise pri velikom SBOM-u),
 * pa ne smije drzati HTTP nit koja je primila upload - klijent dobije odgovor odmah, a
 * analiza tece u pozadini. Zaseban bazen (ne zajednicki Springov) da dugotrajna analiza
 * ne zaguši niti koje sluze ostale zadatke.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "sbomExecutor")
    public Executor sbomExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("sbom-");
        executor.initialize();
        return executor;
    }
}
