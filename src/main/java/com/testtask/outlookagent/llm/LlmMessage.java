package com.testtask.outlookagent.llm;

public final class LlmMessage {

    private final String content;
    private final boolean toolResult;
    private final String toolCallId;
    private final ToolCall assistantToolCall;

    private LlmMessage(String content, boolean toolResult, String toolCallId, ToolCall assistantToolCall) {
        this.content = content;
        this.toolResult = toolResult;
        this.toolCallId = toolCallId;
        this.assistantToolCall = assistantToolCall;
    }

    public static LlmMessage userMessage(String content) {
        return new LlmMessage(content, false, null, null);
    }

    public static LlmMessage toolResult(String content) {
        return toolResult(content, null);
    }

    public static LlmMessage toolResult(String content, String toolCallId) {
        return new LlmMessage(content, true, toolCallId, null);
    }

    public static LlmMessage assistantToolCall(ToolCall toolCall) {
        return new LlmMessage(null, false, null, toolCall);
    }

    public boolean isToolResult() {
        return toolResult;
    }

    public boolean isAssistantToolCall() {
        return assistantToolCall != null;
    }

    public String getContent() {
        return content;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public ToolCall getAssistantToolCall() {
        return assistantToolCall;
    }
}
