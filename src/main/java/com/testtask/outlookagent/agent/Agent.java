package com.testtask.outlookagent.agent;

import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.llm.ToolCall;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;

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
            Tool tool = toolRegistry.findByName(toolCall.getToolName())
                    .orElseThrow(() -> new IllegalStateException("Unknown tool: " + toolCall.getToolName()));
            Object result = tool.execute(toolCall.getArguments());
            messages.add(LlmMessage.toolResult(String.valueOf(result)));
        }

        throw new IllegalStateException("Max steps exceeded");
    }
}
