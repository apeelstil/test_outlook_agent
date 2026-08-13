package com.testtask.outlookagent.tool;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ToolRegistry {

    private final Map<String, Tool> toolsByName = new HashMap<>();

    public void register(Tool tool) {
        toolsByName.put(tool.getName(), tool);
    }

    public Optional<Tool> findByName(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }
}
