package com.testtask.outlookagent.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration boundary (see PLAN.md Application/PollLoop): runs a mail-processing
 * cycle repeatedly, sleeping mail.pollSeconds between cycles. A single cycle failure
 * must not stop the loop (Roadmap #10).
 */
public class PollingLoop {

    private static final Logger logger = LoggerFactory.getLogger(PollingLoop.class);

    public interface Sleeper {
        void sleep(long millis);
    }

    private final Runnable cycle;
    private final long pollMillis;
    private final Sleeper sleeper;

    public PollingLoop(Runnable cycle, int pollSeconds, Sleeper sleeper) {
        if (pollSeconds <= 0) {
            throw new IllegalArgumentException("pollSeconds must be positive, got: " + pollSeconds);
        }
        this.cycle = cycle;
        this.pollMillis = pollSeconds * 1000L;
        this.sleeper = sleeper;
    }

    public void runCycles(int cycles) {
        for (int i = 0; i < cycles; i++) {
            runCycleSafely();
            sleeper.sleep(pollMillis);
        }
    }

    public void runForever() {
        while (true) {
            runCycleSafely();
            sleeper.sleep(pollMillis);
        }
    }

    private void runCycleSafely() {
        try {
            cycle.run();
        } catch (RuntimeException e) {
            logger.warn("event=poll_cycle_failed");
        }
    }
}
