package com.testtask.outlookagent.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * RED spec (Этап 37): a production MockDemoRunner must let a developer manually
 * exercise the full application flow — scripted mock mail, real Agent/tools/stores,
 * deterministic scripted LlmClient — without Outlook, JACOB native runtime, or
 * network LLM calls (see PLAN.md delivery/demo section).
 */
public class MockDemoRunnerTest {

    @Test
    public void runsFullMockProcessingCycleWithoutOutlookOrNetworkAndReturnsReply() {
        MockDemoRunner runner = new MockDemoRunner();

        String replyBody = runner.run();

        assertNotNull("Mock demo run must produce a reply body", replyBody);
        assertFalse("Mock demo reply must not be blank", replyBody.trim().isEmpty());
    }
}
