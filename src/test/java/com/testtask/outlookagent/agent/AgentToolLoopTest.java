package com.testtask.outlookagent.agent;

import static org.junit.Assert.assertEquals;
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
 * RED spec: describes the expected happy-path LLM tool-loop contract
 * (LlmClient/LlmMessage/LlmResponse/ToolCall/Agent) before any production
 * code exists. See PLAN.md TDD roadmap.
 */
public class AgentToolLoopTest {

    @Test
    public void toolCallLoopProducesFinalAnswerUsingToolResult() {
        EchoTool echoTool = new EchoTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);

        Map<String, Object> echoArgs = new HashMap<>();
        echoArgs.put("text", "hello");
        ToolCall toolCall = new ToolCall("echo", echoArgs);

        RecordingMockLlmClient llmClient = new RecordingMockLlmClient(
                LlmResponse.toolCall(toolCall),
                LlmResponse.finalAnswer("Final answer"));

        Agent agent = new Agent(llmClient, registry, 3);

        String result = agent.run("please echo hello");

        assertEquals("Final answer", result);
        assertEquals(2, llmClient.callCount());
        assertEquals(1, echoTool.executionCount());
        assertEquals("hello", echoTool.lastReceivedText());

        List<LlmMessage> secondCallMessages = llmClient.messagesForCall(1);
        boolean containsToolResult = false;
        for (LlmMessage message : secondCallMessages) {
            if (message.isToolResult() && "echo:hello".equals(message.getContent())) {
                containsToolResult = true;
            }
        }
        assertTrue("Second LLM call must include the tool result, not just the original user message",
                containsToolResult);

        List<Tool> toolsPassedFirstCall = llmClient.toolsForCall(0);
        assertEquals(1, toolsPassedFirstCall.size());
        assertEquals("echo", toolsPassedFirstCall.get(0).getName());
    }

    private static class EchoTool implements Tool {

        private int executionCount = 0;
        private String lastReceivedText;

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            executionCount++;
            lastReceivedText = (String) args.get("text");
            return "echo:" + lastReceivedText;
        }

        int executionCount() {
            return executionCount;
        }

        String lastReceivedText() {
            return lastReceivedText;
        }
    }

    private static class RecordingMockLlmClient implements LlmClient {

        private final LlmResponse[] scriptedResponses;
        private final List<List<LlmMessage>> messagesByCall = new ArrayList<>();
        private final List<List<Tool>> toolsByCall = new ArrayList<>();
        private int callIndex = 0;

        RecordingMockLlmClient(LlmResponse... scriptedResponses) {
            this.scriptedResponses = scriptedResponses;
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            messagesByCall.add(new ArrayList<>(messages));
            toolsByCall.add(new ArrayList<>(tools));
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

        List<Tool> toolsForCall(int index) {
            return toolsByCall.get(index);
        }
    }
}
