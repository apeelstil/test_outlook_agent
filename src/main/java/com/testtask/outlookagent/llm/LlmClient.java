package com.testtask.outlookagent.llm;

import com.testtask.outlookagent.tool.Tool;
import java.util.List;

public interface LlmClient {

    LlmResponse chat(List<LlmMessage> messages, List<Tool> tools);
}
