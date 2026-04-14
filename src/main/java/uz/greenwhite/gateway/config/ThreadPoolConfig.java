package uz.greenwhite.gateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ThreadPoolConfig {

    private final ConcurrencyProperties concurrencyProperties;

    /**
     * Dedicated thread pool for HTTP requests.
     *
     * Kafka consumer threads only receive messages and
     * delegate HTTP work to this pool.
     * Result: consumer threads stay free to accept new messages.
     *
     * IMPORTANT: Uses RejectWithLogging instead of CallerRunsPolicy.
     * CallerRunsPolicy was blocking Kafka consumer threads when pool
     * was exhausted, causing complete pipeline stall during cascading failures.
     * RejectWithLogging throws RejectedExecutionException which RequestConsumer
     * catches and re-queues the message to Kafka for later processing.
     */
    @Bean("httpRequestExecutor")
    public ThreadPoolTaskExecutor httpRequestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Core — always alive
        executor.setCorePoolSize(concurrencyProperties.getMinConcurrency());

        // Max — scales up under load
        executor.setMaxPoolSize(concurrencyProperties.getMaxConcurrency()
                * concurrencyProperties.getThreadPoolMultiplier());

        // Queue — tasks wait here when max pool is full
        executor.setQueueCapacity(concurrencyProperties.getThreadPoolQueueCapacity());

        // Idle threads are terminated after 60s
        executor.setKeepAliveSeconds(60);

        // Thread name prefix — for debugging and monitoring
        executor.setThreadNamePrefix("http-req-");

        // Rejection policy — reject task so consumer thread stays free
        executor.setRejectedExecutionHandler(new RejectWithLogging());

        // Allow core threads to timeout (saves resources)
        executor.setAllowCoreThreadTimeOut(true);

        executor.initialize();

        log.info("HTTP Request ThreadPool created: core={}, max={}, queue={}",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize(),
                concurrencyProperties.getThreadPoolQueueCapacity());

        return executor;
    }

    /**
     * Custom rejection handler:
     * When queue is full — reject the task with RejectedExecutionException.
     * RequestConsumer catches this and re-queues the message to Kafka.
     * Consumer thread stays free to process new messages.
     *
     * Previous CallerRunsPolicy was running the task on the Kafka consumer thread,
     * blocking it for up to 69 seconds and causing pipeline stall.
     */
    static class RejectWithLogging implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("HTTP thread pool exhausted! Task rejected. queue={}, active={}, pool={}",
                    executor.getQueue().size(),
                    executor.getActiveCount(),
                    executor.getPoolSize());

            throw new RejectedExecutionException("HTTP thread pool exhausted — task will be re-queued");
        }
    }
}