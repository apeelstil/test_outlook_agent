package com.testtask.outlookagent.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.llm.ToolCall;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * RED spec: describes the expected safe fallback contract for Agent.run()
 * when the LLM keeps requesting tool_calls until maxSteps is exhausted.
 * See PLAN.md TDD roadmap (Roadmap #8 protective test, TZ §9).
 */
public class AgentMaxStepsTest {

    @Test
    public void returnsSafeFallbackWithoutThrowingWhenStepLimitExhausted() {
        LoopTool loopTool = new LoopTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(loopTool);

        ToolCall loopCall = new ToolCall("loop_tool", new HashMap<String, Object>());
        AlwaysLoopingLlmClient llmClient = new AlwaysLoopingLlmClient(LlmResponse.toolCall(loopCall));

        Agent agent = new Agent(llmClient, registry, 2);

        String result = agent.run("start");

        assertEquals("Unable to complete request within step limit", result);
        assertEquals(2, llmClient.callCount());
        assertEquals(2, loopTool.executionCount());

        assertFalse("Fallback must not leak the user message",
                result.contains("start"));
    }

    @Test
    public void singleAllowedStepCanStillReturnFinalAnswer() {
        ToolRegistry registry = new ToolRegistry();
        AlwaysLoopingLlmClient llmClient = new AlwaysLoopingLlmClient(LlmResponse.finalAnswer("Final answer"));

        Agent agent = new Agent(llmClient, registry, 1);

        String result = agent.run("start");

        assertEquals("Final answer", result);
        assertEquals(1, llmClient.callCount());
    }

    private static class LoopTool implements Tool {

        private int executionCount = 0;

        @Override
        public String getName() {
            return "loop_tool";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            executionCount++;
            return "looped";
        }

        int executionCount() {
            return executionCount;
        }
    }

    private static class AlwaysLoopingLlmClient implements LlmClient {

        private final LlmResponse scriptedResponse;
        private final List<List<LlmMessage>> messagesByCall = new ArrayList<>();
        private int callIndex = 0;

        AlwaysLoopingLlmClient(LlmResponse scriptedResponse) {
            this.scriptedResponse = scriptedResponse;
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            messagesByCall.add(new ArrayList<>(messages));
            callIndex++;
            return scriptedResponse;
        }

        int callCount() {
            return callIndex;
        }
    }
}
