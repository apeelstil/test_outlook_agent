package com.testtask.outlookagent.tool;

import static org.junit.Assert.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import org.junit.Test;

/**
 * RED spec (Roadmap #5): describes the expected CurrentDateTimeTool contract
 * before any production code exists. See PLAN.md TDD roadmap.
 */
public class CurrentDateTimeToolTest {

    @Test
    public void hasStableToolName() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-13T10:30:00Z"), ZoneOffset.UTC);
        CurrentDateTimeTool tool = new CurrentDateTimeTool(fixedClock);

        assertEquals("current_datetime", tool.getName());
    }

    @Test
    public void executeReturnsIsoInstantFromInjectedClock() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-13T10:30:00Z"), ZoneOffset.UTC);
        CurrentDateTimeTool tool = new CurrentDateTimeTool(fixedClock);

        Object result = tool.execute(Collections.emptyMap());

        assertEquals("2026-08-13T10:30:00Z", result);
    }
}
