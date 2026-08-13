package com.testtask.outlookagent.llm;

public final class LlmMessage {

    private final String content;
    private final boolean toolResult;

    private LlmMessage(String content, boolean toolResult) {
        this.content = content;
        this.toolResult = toolResult;
    }

    public static LlmMessage userMessage(String content) {
        return new LlmMessage(content, false);
    }

    public static LlmMessage toolResult(String content) {
        return new LlmMessage(content, true);
    }

    public boolean isToolResult() {
        return toolResult;
    }

    public String getContent() {
        return content;
    }
}
