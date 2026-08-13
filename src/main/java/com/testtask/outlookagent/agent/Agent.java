package com.testtask.outlookagent.agent;

import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.llm.ToolCall;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Agent {

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final int maxSteps;

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, int maxSteps) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.maxSteps = maxSteps;
    }

    public String run(String userMessage) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userMessage(userMessage));
        List<Tool> tools = toolRegistry.getAll();

        for (int step = 0; step < maxSteps; step++) {
            LlmResponse response = llmClient.chat(messages, tools);

            if (!response.isToolCall()) {
                return response.getFinalAnswer();
            }

            ToolCall toolCall = response.getToolCall();
            Optional<Tool> tool = toolRegistry.findByName(toolCall.getToolName());
            if (!tool.isPresent()) {
                messages.add(LlmMessage.toolResult("Error: unknown tool requested"));
                continue;
            }

            try {
                Object result = tool.get().execute(toolCall.getArguments());
                messages.add(LlmMessage.toolResult(String.valueOf(result)));
            } catch (IllegalArgumentException e) {
                messages.add(LlmMessage.toolResult("Error: invalid tool arguments - " + e.getMessage()));
            } catch (RuntimeException e) {
                messages.add(LlmMessage.toolResult("Error: tool execution failed"));
            }
        }

        return "Unable to complete request within step limit";
    }
}
