package com.testtask.outlookagent.tool;

import java.util.Map;

public interface Tool {

    String getName();

    Object execute(Map<String, Object> args);
}
