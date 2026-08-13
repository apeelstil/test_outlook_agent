package com.testtask.outlookagent.llm;

import java.util.Map;

public final class ToolCall {

    private final String id;
    private final String toolName;
    private final Map<String, Object> arguments;

    public ToolCall(String toolName, Map<String, Object> arguments) {
        this(null, toolName, arguments);
    }

    public ToolCall(String id, String toolName, Map<String, Object> arguments) {
        this.id = id;
        this.toolName = toolName;
        this.arguments = arguments;
    }

    public String getId() {
        return id;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }
}
