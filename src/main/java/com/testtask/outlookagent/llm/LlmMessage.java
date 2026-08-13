package com.testtask.outlookagent.llm;

public final class LlmMessage {

    private final String content;
    private final boolean toolResult;
    private final String toolCallId;

    private LlmMessage(String content, boolean toolResult, String toolCallId) {
        this.content = content;
        this.toolResult = toolResult;
        this.toolCallId = toolCallId;
    }

    public static LlmMessage userMessage(String content) {
        return new LlmMessage(content, false, null);
    }

    public static LlmMessage toolResult(String content) {
        return toolResult(content, null);
    }

    public static LlmMessage toolResult(String content, String toolCallId) {
        return new LlmMessage(content, true, toolCallId);
    }

    public boolean isToolResult() {
        return toolResult;
    }

    public String getContent() {
        return content;
    }

    public String getToolCallId() {
        return toolCallId;
    }
}
