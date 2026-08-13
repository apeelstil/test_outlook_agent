package com.testtask.outlookagent.tool;

import java.util.Collections;
import java.util.Map;

public interface Tool {

    String getName();

    Object execute(Map<String, Object> args);

    default String getDescription() {
        return "";
    }

    default Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        return schema;
    }
}
