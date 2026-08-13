package com.testtask.outlookagent.agent;

import com.testtask.outlookagent.audit.AuditEvent;
import com.testtask.outlookagent.audit.AuditJournal;
import com.testtask.outlookagent.audit.NoOpAuditJournal;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.llm.ToolCall;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Agent {

    private static final Logger logger = LoggerFactory.getLogger(Agent.class);

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final int maxSteps;
    private final AuditJournal auditJournal;

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, int maxSteps) {
        this(llmClient, toolRegistry, maxSteps, new NoOpAuditJournal());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, int maxSteps, AuditJournal auditJournal) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.maxSteps = maxSteps;
        this.auditJournal = auditJournal;
    }

    public String run(String userMessage) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userMessage(userMessage));
        List<Tool> tools = toolRegistry.getAll();

        for (int step = 0; step < maxSteps; step++) {
            LlmResponse response;
            try {
                response = llmClient.chat(messages, tools);
            } catch (RuntimeException e) {
                logger.warn("event=llm_failed");
                return "Unable to process request at this time";
            }

            if (!response.isToolCall()) {
                return response.getFinalAnswer();
            }

            ToolCall toolCall = response.getToolCall();
            messages.add(LlmMessage.assistantToolCall(toolCall));

            Optional<Tool> tool = toolRegistry.findByName(toolCall.getToolName());
            if (!tool.isPresent()) {
                messages.add(LlmMessage.toolResult("Error: unknown tool requested", toolCall.getId()));
                continue;
            }

            logger.info("event=agent_tool_call tool={}", toolCall.getToolName());
            auditJournal.append(new AuditEvent("agent_tool_call", null, toolCall.getToolName(),
                    System.currentTimeMillis()));

            try {
                Object result = tool.get().execute(toolCall.getArguments());
                messages.add(LlmMessage.toolResult(String.valueOf(result), toolCall.getId()));
            } catch (IllegalArgumentException e) {
                messages.add(LlmMessage.toolResult("Error: invalid tool arguments - " + e.getMessage(), toolCall.getId()));
            } catch (RuntimeException e) {
                messages.add(LlmMessage.toolResult("Error: tool execution failed", toolCall.getId()));
            }
        }

        return "Unable to complete request within step limit";
    }
}
