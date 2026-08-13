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
}
