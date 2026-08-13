package com.testtask.outlookagent.tool;

import com.testtask.outlookagent.store.FileReminderStore;
import java.util.Map;

public class FindItemsTool implements Tool {

    private final FileReminderStore store;

    public FindItemsTool(FileReminderStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return "find_items";
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Object query = args.get("query");

        if (!(query instanceof String)) {
            throw new IllegalArgumentException("Missing required argument: query");
        }

        return store.find((String) query);
    }

    @Override
    public String getDescription() {
        return "Finds stored reminders matching a query.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> queryProperty = new java.util.LinkedHashMap<>();
        queryProperty.put("type", "string");

        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("query", queryProperty);

        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.Collections.singletonList("query"));
        return schema;
    }
}
