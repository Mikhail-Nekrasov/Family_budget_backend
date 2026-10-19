package fi.haagahelia.accounting.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.repository.SyncDownloadRepository;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "sync.download.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class SyncDownloadCleanup {
    private final SyncDownloadRepository repository;

    public SyncDownloadCleanup(SyncDownloadRepository repository) {
        this.repository = repository;
    }

    @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    @Transactional
    public void cleanup() {
        repository.cleanup();
    }
}
