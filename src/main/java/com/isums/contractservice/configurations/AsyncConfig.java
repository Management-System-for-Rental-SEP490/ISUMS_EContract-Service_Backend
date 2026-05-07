package com.isums.contractservice.configurations;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables {@link org.springframework.scheduling.annotation.Async @Async}
 * and defines dedicated executors so each background concern (snapshot
 * refresh, outbox polling, etc.) has its own bounded thread pool and
 * can be tuned / monitored independently.
 *
 * <p>Dedicated pools (not the shared Spring "applicationTaskExecutor")
 * because snapshot refresh makes blocking HTTP calls to VNPT that can
 * tie up threads for up to ~47 seconds per attempt. Sharing the common
 * pool would risk starving other async work (e.g. @TransactionalEventListener
 * handlers) when VNPT has an outage.
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /** Pool name used by {@code @Async("snapshotRefreshExecutor")}. */
    public static final String SNAPSHOT_REFRESH_EXECUTOR = "snapshotRefreshExecutor";

    /**
     * Thread pool for async snapshot refreshes from VNPT.
     *
     * <p>Sizing rationale:
     * <ul>
     *   <li>coreSize=3 — handles concurrent signings on a typical day
     *       (landlord + a few tenants signing back-to-back).</li>
     *   <li>maxSize=8 — burst capacity for end-of-month batch signings.</li>
     *   <li>queueCapacity=200 — contracts backlog during VNPT outage.
     *       If this fills up, {@code AbortPolicy} will throw and the
     *       caller (sign API) sees a RejectedExecutionException on the
     *       after-commit callback. That's fine: the sign itself is
     *       already committed on VNPT's side, and the admin refresh
     *       endpoint can catch it up later.</li>
     *   <li>keepAlive=60s — idle threads are released after a minute so
     *       we don't pin 3 threads forever during quiet periods.</li>
     * </ul>
     */
    @Bean(name = SNAPSHOT_REFRESH_EXECUTOR)
    public TaskExecutor snapshotRefreshExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(3);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(200);
        ex.setKeepAliveSeconds(60);
        ex.setAllowCoreThreadTimeOut(true);
        ex.setThreadNamePrefix("snapshot-refresh-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // Let in-flight refreshes finish on shutdown (up to 10s each)
        // so we don't leave S3 in an intermediate state where the new
        // PDF is uploaded but the contract row hasn't been updated.
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        log.info("[AsyncConfig] {} initialized core=3 max=8 queue=200", SNAPSHOT_REFRESH_EXECUTOR);
        return ex;
    }
}
