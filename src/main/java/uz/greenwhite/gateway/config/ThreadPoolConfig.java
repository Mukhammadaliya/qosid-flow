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
