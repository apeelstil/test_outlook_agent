package com.testtask.outlookagent.tool;

import java.time.Clock;
import java.util.Map;

public class CurrentDateTimeTool implements Tool {

    private final Clock clock;

    public CurrentDateTimeTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String getName() {
        return "current_datetime";
    }

    @Override
    public Object execute(Map<String, Object> args) {
        return clock.instant().toString();
    }
}
