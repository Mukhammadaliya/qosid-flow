package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code cbRequeueTimer} (rule 40) is a {@link ScheduledExecutorService} bean, and Spring Boot's
 * auto-configured {@code taskScheduler} backs off on
 * {@code @ConditionalOnMissingBean({TaskScheduler, ScheduledExecutorService})}. Without an explicit
 * {@code taskScheduler} bean, {@code @Scheduled} therefore adopts the CB timer's two threads and
 * the E1 pull loop ends up sharing them with parked CB hand-backs that block on
 * {@code KafkaTemplate.send}.
 *
 * <p>The defect is invisible in behaviour — nothing is lost, no state leaks — and only shows up in
 * thread names, so this test watches the thread a real {@code @Scheduled} method runs on.
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:scheduler_isolation_test;DB_CLOSE_DELAY=-1",
        "gateway.settings.encryption-key=test-key-0123456789",
        "gateway.rocksdb.path=./target/test-data/rocksdb-scheduler-isolation",
        // Deliberately 3: different from Boot's default (1) AND from the CB timer's thread
        // count (2), so the pool-size assertion proves spring.task.scheduling.* still reaches
        // the scheduler rather than a hard-coded size. It also keeps the probe off the tail of
        // ConcurrencyMonitorService's scheduled run, which blocks ~10s on a Kafka AdminClient
        // when no broker is present.
        "spring.task.scheduling.pool.size=3"
})
class SchedulerIsolationTest {

    private static final String CB_TIMER_THREAD = "cb-requeue-timer";

    @Autowired
    ApplicationContext ctx;

    @Autowired
    ScheduledThreadProbe probe;

    @Autowired
    @Qualifier("cbRequeueTimer")
    ScheduledExecutorService cbRequeueTimer;

    @Test
    void scheduledWorkDoesNotRunOnTheCbRequeueTimerThreads() throws Exception {
        String thread = probe.awaitThreadName();

        assertThat(thread)
                .as("A @Scheduled method must not run on the CB requeue timer — RequestPuller's "
                        + "blocking source call would then be entangled with parked CB hand-backs")
                .doesNotStartWith(CB_TIMER_THREAD);
        assertThat(thread)
                .as("Boot's default scheduling thread-name prefix is what production logs show "
                        + "on the pull loop")
                .startsWith("scheduling-");
    }

    @Test
    void taskSchedulerBeanExistsAndHonoursSpringTaskSchedulingProperties() {
        assertThat(ctx.getBeanNamesForType(TaskScheduler.class))
                .as("An explicit taskScheduler bean is what keeps Boot's back-off irrelevant")
                .containsExactly("taskScheduler");

        ThreadPoolTaskScheduler scheduler = ctx.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
        assertThat(scheduler.getPoolSize())
                .as("spring.task.scheduling.pool.size must still apply — never hard-code the size")
                .isEqualTo(3);
    }

    @Test
    void cbRequeueTimerRemainsItsOwnSeparatelyNamedBean() throws Exception {
        assertThat(ctx.getBeanNamesForType(ScheduledExecutorService.class))
                .as("Each timer is its own named bean, and taskScheduler is deliberately NOT a "
                        + "ScheduledExecutorService — otherwise Boot's back-off returns")
                .containsExactlyInAnyOrder("cbRequeueTimer", "alertCoalesceTimer");

        CompletableFuture<String> name = new CompletableFuture<>();
        cbRequeueTimer.execute(() -> name.complete(Thread.currentThread().getName()));
        assertThat(name.get(10, TimeUnit.SECONDS))
                .as("CbRequeueScheduler's threads keep their prefix — rule 40's guards read it")
                .isEqualTo(CB_TIMER_THREAD);
    }

    /**
     * A DLQ-alert window flush makes a blocking Telegram call (5 s connect + 5 s read). If it ran
     * on the CB timer's two threads it could delay a parked request's requeue by ten seconds per
     * stuck alert — the pipeline's recovery must not depend on a notification channel's health.
     */
    @Test
    void alertCoalescingRunsOffTheCbRequeueTimer() throws Exception {
        ScheduledExecutorService alertTimer =
                ctx.getBean("alertCoalesceTimer", ScheduledExecutorService.class);

        CompletableFuture<String> name = new CompletableFuture<>();
        alertTimer.execute(() -> name.complete(Thread.currentThread().getName()));

        assertThat(name.get(10, TimeUnit.SECONDS))
                .as("A blocking Telegram flush must never occupy a CB requeue thread")
                .isEqualTo("alert-coalesce-timer")
                .doesNotStartWith(CB_TIMER_THREAD);
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ScheduledThreadProbe scheduledThreadProbe() {
            return new ScheduledThreadProbe();
        }
    }

    /**
     * A genuine {@code @Scheduled} method rather than a hand-submitted task: the defect is in how
     * Spring resolves the scheduler for {@code @Scheduled}, so nothing else observes it.
     */
    static class ScheduledThreadProbe {

        private final CompletableFuture<String> threadName = new CompletableFuture<>();

        @Scheduled(initialDelay = 0, fixedDelay = 3_600_000)
        void record() {
            threadName.complete(Thread.currentThread().getName());
        }

        String awaitThreadName() throws Exception {
            return threadName.get(30, TimeUnit.SECONDS);
        }
    }
}
