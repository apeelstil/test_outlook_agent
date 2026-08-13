package com.testtask.outlookagent.llm;

public final class LlmResponse {

    private final ToolCall toolCall;
    private final String finalAnswer;

    private LlmResponse(ToolCall toolCall, String finalAnswer) {
        this.toolCall = toolCall;
        this.finalAnswer = finalAnswer;
    }

    public static LlmResponse toolCall(ToolCall toolCall) {
        return new LlmResponse(toolCall, null);
    }

    public static LlmResponse finalAnswer(String finalAnswer) {
        return new LlmResponse(null, finalAnswer);
    }

    public boolean isToolCall() {
        return toolCall != null;
    }

    public ToolCall getToolCall() {
        return toolCall;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }
}
