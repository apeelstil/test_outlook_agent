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
}
