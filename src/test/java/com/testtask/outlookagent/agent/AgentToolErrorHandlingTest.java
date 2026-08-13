package com.testtask.outlookagent.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
 * RED spec: describes the expected graceful error-handling contract for
 * Agent.run() when a tool_call is unknown, malformed, or throws during
 * execution. See PLAN.md TDD roadmap.
 */
public class AgentToolErrorHandlingTest {

    @Test
    public void handlesUnknownToolWithoutThrowing() {
        ToolRegistry registry = new ToolRegistry();

        ToolCall unknownCall = new ToolCall("does_not_exist", new HashMap<String, Object>());

        RecordingMockLlmClient llmClient = new RecordingMockLlmClient(
                LlmResponse.toolCall(unknownCall),
                LlmResponse.finalAnswer("Handled unknown tool"));

        Agent agent = new Agent(llmClient, registry, 3);

        String result = agent.run("please call an unknown tool");

        assertEquals("Handled unknown tool", result);
        assertEquals(2, llmClient.callCount());

        LlmMessage toolResultMessage = findToolResultMessage(llmClient.messagesForCall(1));
        assertTrue("Second LLM call must include a tool-result message describing the error",
                toolResultMessage != null);
        String errorContent = toolResultMessage.getContent().toLowerCase();
        assertTrue("Error content should indicate the tool is unknown",
                errorContent.contains("unknown") || errorContent.contains("not found") || errorContent.contains("no such"));
        assertFalse("Error content must not contain a stacktrace",
                errorContent.contains(".java:") || errorContent.contains("at com.testtask"));
    }

    @Test
    public void handlesMissingRequiredArgumentWithoutThrowing() {
        RequiredArgTool requiredArgTool = new RequiredArgTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(requiredArgTool);

        ToolCall missingArgCall = new ToolCall("requires_value", new HashMap<String, Object>());

        RecordingMockLlmClient llmClient = new RecordingMockLlmClient(
                LlmResponse.toolCall(missingArgCall),
                LlmResponse.finalAnswer("Handled invalid arguments"));

        Agent agent = new Agent(llmClient, registry, 3);

        String result = agent.run("please call requires_value without the argument");

        assertEquals("Handled invalid arguments", result);
        assertEquals(1, requiredArgTool.invocationCount());
        assertEquals(2, llmClient.callCount());

        LlmMessage toolResultMessage = findToolResultMessage(llmClient.messagesForCall(1));
        assertTrue("Second LLM call must include a tool-result message describing the error",
                toolResultMessage != null);
        String errorContent = toolResultMessage.getContent().toLowerCase();
        assertTrue("Error content should mention the missing argument",
                errorContent.contains("value"));
        assertFalse("Error content must not contain a stacktrace",
                errorContent.contains(".java:") || errorContent.contains("at com.testtask"));
    }

    @Test
    public void handlesToolExecutionExceptionWithoutThrowing() {
        FailingTool failingTool = new FailingTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(failingTool);

        ToolCall failingCall = new ToolCall("failing_tool", new HashMap<String, Object>());

        RecordingMockLlmClient llmClient = new RecordingMockLlmClient(
                LlmResponse.toolCall(failingCall),
                LlmResponse.finalAnswer("Handled tool failure"));

        Agent agent = new Agent(llmClient, registry, 3);

        String result = agent.run("please call the failing tool");

        assertEquals("Handled tool failure", result);
        assertEquals(2, llmClient.callCount());

        LlmMessage toolResultMessage = findToolResultMessage(llmClient.messagesForCall(1));
        assertTrue("Second LLM call must include a tool-result message describing the error",
                toolResultMessage != null);
        String errorContent = toolResultMessage.getContent();
        assertFalse("Error content must not contain a stacktrace",
                errorContent.contains(".java:") || errorContent.contains("at com.testtask"));
        assertFalse("Error content must not leak the raw exception message",
                errorContent.contains("boom-test-failure"));
    }

    @Test
    public void neverLeaksRawToolArgumentExceptionMessageToLlm() {
        SecretLeakingTool secretLeakingTool = new SecretLeakingTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(secretLeakingTool);

        ToolCall secretCall = new ToolCall("secret_leaking_tool", new HashMap<String, Object>());

        RecordingMockLlmClient llmClient = new RecordingMockLlmClient(
                LlmResponse.toolCall(secretCall),
                LlmResponse.finalAnswer("Handled invalid arguments"));

        Agent agent = new Agent(llmClient, registry, 3);

        String result = agent.run("please call the secret leaking tool");

        assertEquals("Handled invalid arguments", result);
        assertEquals(2, llmClient.callCount());

        LlmMessage toolResultMessage = findToolResultMessage(llmClient.messagesForCall(1));
        assertTrue("Second LLM call must include a tool-result message describing the error",
                toolResultMessage != null);
        String errorContent = toolResultMessage.getContent().toLowerCase();
        assertTrue("Error content should indicate invalid tool arguments via a generic marker",
                errorContent.contains("invalid") && errorContent.contains("argument"));

        for (LlmMessage message : llmClient.messagesForCall(1)) {
            String content = message.getContent();
            assertFalse("No message sent to the next LLM call may contain the raw exception message",
                    content != null && content.contains(SecretLeakingTool.SECRET_MESSAGE));
        }
    }

    private static LlmMessage findToolResultMessage(List<LlmMessage> messages) {
        for (LlmMessage message : messages) {
            if (message.isToolResult()) {
                return message;
            }
        }
        return null;
    }

    private static class RequiredArgTool implements Tool {

        private int invocationCount = 0;

        @Override
        public String getName() {
            return "requires_value";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            invocationCount++;
            Object value = args.get("value");
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("Missing required argument: value");
            }
            return "ok:" + value;
        }

        int invocationCount() {
            return invocationCount;
        }
    }

    private static class FailingTool implements Tool {

        @Override
        public String getName() {
            return "failing_tool";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            throw new RuntimeException("boom-test-failure");
        }
    }

    private static class SecretLeakingTool implements Tool {

        static final String SECRET_MESSAGE = "secret-user-value-123";

        @Override
        public String getName() {
            return "secret_leaking_tool";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            throw new IllegalArgumentException(SECRET_MESSAGE);
        }
    }

    private static class RecordingMockLlmClient implements LlmClient {

        private final LlmResponse[] scriptedResponses;
        private final List<List<LlmMessage>> messagesByCall = new ArrayList<>();
        private int callIndex = 0;

        RecordingMockLlmClient(LlmResponse... scriptedResponses) {
            this.scriptedResponses = scriptedResponses;
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            messagesByCall.add(new ArrayList<>(messages));
            LlmResponse response = scriptedResponses[callIndex];
            callIndex++;
            return response;
        }

        int callCount() {
            return callIndex;
        }

        List<LlmMessage> messagesForCall(int index) {
            return messagesByCall.get(index);
        }
    }
}
