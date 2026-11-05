package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.application.PasswordRecoveryWorkQueue;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BoundedPasswordRecoveryQueue implements PasswordRecoveryWorkQueue {
    private final AtomicInteger sequence = new AtomicInteger();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "password-recovery-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    @Override public void submit(Runnable work) { executor.execute(work); }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
