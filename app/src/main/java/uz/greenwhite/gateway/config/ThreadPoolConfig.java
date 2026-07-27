package uz.greenwhite.gateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ThreadPoolConfig {

    private final ConcurrencyProperties props;

    @Bean("oracleHttpExecutor")
    public ThreadPoolTaskExecutor oracleHttpExecutor() {
        return buildExecutor(props.getOracleMin(), props.getOracleMax(), "oracle-http-");
    }

    @Bean("apiHttpExecutor")
    public ThreadPoolTaskExecutor apiHttpExecutor() {
        return buildExecutor(props.getApiMin(), props.getApiMax(), "api-http-");
    }

    /**
     * Dedicated pool for asynchronous OAuth2 token refreshes triggered by the
     * stale-while-revalidate path. Small and bounded — when full we drop the
     * task because the next request will trigger another refresh anyway.
     */
    @Bean("oauth2RefreshExecutor")
    public ThreadPoolTaskExecutor oauth2RefreshExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("oauth2-refresh-");
        executor.setRejectedExecutionHandler((r, e) ->
                log.debug("OAuth2 refresh rejected — executor saturated, will retry on next request"));
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        log.info("ThreadPool [oauth2-refresh-] created: core={}, max={}, queue=50",
                executor.getCorePoolSize(), executor.getMaxPoolSize());
        return executor;
    }

    /**
     * Shared timer for parked CB requeues. Two threads is plenty: the tasks only re-check a
     * circuit state and hand off to the Kafka producer.
     *
     * <p>Destruction order matters: the consumers that depend on this bean are destroyed
     * first (their {@code @PreDestroy} drains every parked requeue), and Kafka listener
     * containers — being {@code SmartLifecycle} — are stopped before any bean destruction
     * begins, so nothing can call {@code schedule(...)} on an already-shut-down executor.
     */
    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService cbRequeueTimer() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "cb-requeue-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * The pool behind every {@code @Scheduled} method — declared explicitly, and it must stay that
     * way. Spring Boot's auto-configured {@code taskScheduler} carries
     * {@code @ConditionalOnMissingBean({TaskScheduler, ScheduledExecutorService})}, so the
     * {@link #cbRequeueTimer()} bean above silently switches it off; {@code @Scheduled} then adopts
     * the CB timer's two threads and the E1 pull loop's blocking source call shares them with
     * hand-backs that can block on {@code KafkaTemplate.send}. Deleting this bean as
     * "redundant with Boot's default" re-creates that entanglement without changing any behaviour
     * a test could otherwise see — only thread names.
     *
     * <p>Built from Boot's own {@code ThreadPoolTaskSchedulerBuilder} (that bean is unconditional,
     * so it survives the back-off) rather than configured by hand, so every
     * {@code spring.task.scheduling.*} property — pool size, thread-name prefix, shutdown
     * behaviour — keeps the meaning the Spring Boot reference documentation gives it.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    private ThreadPoolTaskExecutor buildExecutor(int min, int max, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(min);
        executor.setMaxPoolSize(max * props.getThreadPoolMultiplier());
        executor.setQueueCapacity(props.getThreadPoolQueueCapacity());
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new RejectWithLogging());
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();

        log.info("ThreadPool [{}] created: core={}, max={}, queue={}",
                prefix, executor.getCorePoolSize(), executor.getMaxPoolSize(),
                props.getThreadPoolQueueCapacity());
        return executor;
    }

    static class RejectWithLogging implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("HTTP thread pool exhausted! Task rejected. queue={}, active={}, pool={}",
                    executor.getQueue().size(), executor.getActiveCount(), executor.getPoolSize());
            throw new RejectedExecutionException("HTTP thread pool exhausted — task will be re-queued");
        }
    }
}
