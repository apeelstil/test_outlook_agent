package com.testtask.outlookagent.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.llm.ToolCall;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * RED spec: describes the expected graceful fallback contract for
 * Agent.run() when LlmClient.chat(...) itself fails (infrastructure
 * failure), as opposed to a malformed/unknown tool_call or maxSteps
 * exhaustion. See PLAN.md TDD roadmap.
 */
public class AgentLlmFailureTest {

    @Test
    public void returnsSafeFallbackWithoutThrowingWhenFirstLlmCallFails() {
        FailingLlmClient llmClient = new FailingLlmClient(1, "secret-internal-llm-error");
        ToolRegistry registry = new ToolRegistry();
        Agent agent = new Agent(llmClient, registry, 3);

        String result = agent.run("private user request");

        assertEquals("Unable to process request at this time", result);
        assertEquals(1, llmClient.callCount());
        assertFalse("Fallback must not leak the raw exception message",
                result.contains("secret-internal-llm-error"));
        assertFalse("Fallback must not leak the original user message",
                result.contains("private user request"));
    }

    @Test
    public void returnsSafeFallbackWithoutThrowingWhenLlmFailsAfterSuccessfulToolCall() {
        EchoTool echoTool = new EchoTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);

        Map<String, Object> echoArgs = new HashMap<>();
        echoArgs.put("text", "hello");
        ToolCall toolCall = new ToolCall("echo", echoArgs);

        ScriptedThenFailingLlmClient llmClient = new ScriptedThenFailingLlmClient(
                LlmResponse.toolCall(toolCall), "second-call-internal-error");

        Agent agent = new Agent(llmClient, registry, 3);

        String result = agent.run("private user request");

        assertEquals(1, echoTool.executionCount());
        assertEquals(2, llmClient.callCount());
        assertEquals("Unable to process request at this time", result);
        assertFalse("Fallback must not leak the raw exception message",
                result.contains("second-call-internal-error"));
    }

    private static class EchoTool implements Tool {

        private int executionCount = 0;

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            executionCount++;
            return "echo:" + args.get("text");
        }

        int executionCount() {
            return executionCount;
        }
    }

    private static class FailingLlmClient implements LlmClient {

        private final int failOnCall;
        private final String failureMessage;
        private int callIndex = 0;

        FailingLlmClient(int failOnCall, String failureMessage) {
            this.failOnCall = failOnCall;
            this.failureMessage = failureMessage;
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            callIndex++;
            if (callIndex == failOnCall) {
                throw new RuntimeException(failureMessage);
            }
            throw new IllegalStateException("Unexpected additional LlmClient.chat call: " + callIndex);
        }

        int callCount() {
            return callIndex;
        }
    }

    private static class ScriptedThenFailingLlmClient implements LlmClient {

        private final LlmResponse firstResponse;
        private final String secondCallFailureMessage;
        private int callIndex = 0;

        ScriptedThenFailingLlmClient(LlmResponse firstResponse, String secondCallFailureMessage) {
            this.firstResponse = firstResponse;
            this.secondCallFailureMessage = secondCallFailureMessage;
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            callIndex++;
            if (callIndex == 1) {
                return firstResponse;
            }
            if (callIndex == 2) {
                throw new RuntimeException(secondCallFailureMessage);
            }
            throw new IllegalStateException("Unexpected additional LlmClient.chat call: " + callIndex);
        }

        int callCount() {
            return callIndex;
        }
    }
}
