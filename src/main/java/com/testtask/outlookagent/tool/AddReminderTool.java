package com.testtask.outlookagent.tool;

import com.testtask.outlookagent.store.FileReminderStore;
import com.testtask.outlookagent.store.Reminder;
import java.util.Map;

public class AddReminderTool implements Tool {

    private final FileReminderStore store;

    public AddReminderTool(FileReminderStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return "add_reminder";
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Object text = args.get("text");
        Object dueIso = args.get("dueIso");

        if (!(text instanceof String)) {
            throw new IllegalArgumentException("Missing required argument: text");
        }
        if (!(dueIso instanceof String)) {
            throw new IllegalArgumentException("Missing required argument: dueIso");
        }

        store.add(new Reminder((String) text, (String) dueIso));

        return "Reminder added";
    }

    @Override
    public String getDescription() {
        return "Adds a reminder with text and an ISO-8601 due date.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> textProperty = new java.util.LinkedHashMap<>();
        textProperty.put("type", "string");

        Map<String, Object> dueIsoProperty = new java.util.LinkedHashMap<>();
        dueIsoProperty.put("type", "string");

        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("text", textProperty);
        properties.put("dueIso", dueIsoProperty);

        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.Arrays.asList("text", "dueIso"));
        return schema;
    }
}
