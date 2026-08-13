package com.testtask.outlookagent.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * RED spec (Этап 38): minimal PollingLoop contract — runs the mail-processing
 * cycle, waits mail.pollSeconds between cycles via an injectable Sleeper (no
 * real waiting in tests), keeps looping when a single cycle fails, and fails
 * fast on an invalid (non-positive) pollSeconds. See PLAN.md
 * Application/PollLoop orchestration boundary and Roadmap #10 (a cycle error
 * must not end the loop).
 */
public class PollingLoopTest {

    @Test
    public void runCyclesInvokesCycleActionEachTimeAndSleepsConfiguredSecondsBetweenCycles() {
        AtomicInteger cycleCount = new AtomicInteger(0);
        List<Long> recordedSleepsMillis = new ArrayList<>();

        PollingLoop loop = new PollingLoop(cycleCount::incrementAndGet, 30, recordSleepsInto(recordedSleepsMillis));

        loop.runCycles(3);

        assertEquals(3, cycleCount.get());
        assertEquals(3, recordedSleepsMillis.size());
        for (long sleepMillis : recordedSleepsMillis) {
            assertEquals(30_000L, sleepMillis);
        }
    }

    @Test
    public void runCyclesContinuesAfterASingleCycleThrows() {
        AtomicInteger cycleCount = new AtomicInteger(0);
        List<Long> recordedSleepsMillis = new ArrayList<>();

        Runnable cycle = () -> {
            int attempt = cycleCount.incrementAndGet();
            if (attempt == 1) {
                throw new RuntimeException("simulated failure in first cycle");
            }
        };

        PollingLoop loop = new PollingLoop(cycle, 5, recordSleepsInto(recordedSleepsMillis));

        loop.runCycles(2);

        assertEquals("A failing cycle must not stop later cycles from running", 2, cycleCount.get());
        assertEquals(2, recordedSleepsMillis.size());
    }

    @Test
    public void constructorFailsFastOnZeroPollSeconds() {
        try {
            new PollingLoop(() -> { }, 0, recordSleepsInto(new ArrayList<>()));
            fail("Expected fail-fast on non-positive pollSeconds");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("pollSeconds"));
        }
    }

    @Test
    public void constructorFailsFastOnNegativePollSeconds() {
        try {
            new PollingLoop(() -> { }, -1, recordSleepsInto(new ArrayList<>()));
            fail("Expected fail-fast on non-positive pollSeconds");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().trim().isEmpty());
        }
    }

    private static PollingLoop.Sleeper recordSleepsInto(List<Long> recordedSleepsMillis) {
        return millis -> recordedSleepsMillis.add(millis);
    }
}
