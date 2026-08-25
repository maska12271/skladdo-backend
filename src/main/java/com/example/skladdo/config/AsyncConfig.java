package com.example.skladdo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * The pool behind {@code @Async}, used for work a caller must not be made to wait for.
 *
 * <p>Chiefly outbound mail. Sending one message means a full SMTP conversation with an external host -
 * connect, STARTTLS, authenticate, transfer, quit - which takes seconds on a good day and can take a
 * great deal longer when the provider is slow. Doing that inside the request meant creating a user sat
 * there spinning for the length of someone else's network round trip, for a result the caller could not
 * act on anyway: the response already carries a copyable link as the fallback.</p>
 *
 * <p>Deliberately small. This is a background courtesy, not throughput to be maximised, and an unbounded
 * pool would let a slow mail host accumulate threads until it took the application down with it. When the
 * queue is full the caller's own thread runs the task - slow, but nothing is silently dropped.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("mailExecutor")
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mail-");
        // Finish what is queued on the way down rather than dropping invitations mid-deploy.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
