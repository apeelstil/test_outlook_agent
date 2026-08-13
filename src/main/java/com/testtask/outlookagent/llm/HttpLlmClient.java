package com.testtask.outlookagent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.testtask.outlookagent.tool.Tool;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible Chat Completions adapter. Endpoint/model/apiKey/timeoutMs are supplied by
 * the caller; this class does not know about environment variables or config files.
 */
public class HttpLlmClient implements LlmClient {

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final int timeoutMs;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpLlmClient(String endpoint, String model, String apiKey, long timeoutMs) {
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutMs = (int) timeoutMs;
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
        String requestBody = buildRequestBody(messages, tools);
        String responseBody = send(requestBody);
        return parseResponse(responseBody);
    }

    private String buildRequestBody(List<LlmMessage> messages, List<Tool> tools) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);

        ArrayNode messagesNode = root.putArray("messages");
        for (LlmMessage message : messages) {
            ObjectNode messageNode = messagesNode.addObject();
            if (message.isAssistantToolCall()) {
                ToolCall toolCall = message.getAssistantToolCall();
                messageNode.put("role", "assistant");
                ArrayNode toolCallsNode = messageNode.putArray("tool_calls");
                ObjectNode toolCallNode = toolCallsNode.addObject();
                toolCallNode.put("id", toolCall.getId());
                toolCallNode.put("type", "function");
                ObjectNode functionNode = toolCallNode.putObject("function");
                functionNode.put("name", toolCall.getToolName());
                functionNode.put("arguments", serializeArguments(toolCall.getArguments()));
            } else if (message.isToolResult()) {
                messageNode.put("role", "tool");
                messageNode.put("tool_call_id", message.getToolCallId());
                messageNode.put("content", message.getContent());
            } else {
                messageNode.put("role", "user");
                messageNode.put("content", message.getContent());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsNode.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.getName());
                functionNode.put("description", tool.getDescription());
                functionNode.set("parameters", mapper.valueToTree(tool.getParametersSchema()));
            }
        }

        try {
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new LlmClientException("Failed to serialize LLM request");
        }
    }

    private String serializeArguments(Map<String, Object> arguments) {
        try {
            return mapper.writeValueAsString(arguments == null ? java.util.Collections.emptyMap() : arguments);
        } catch (IOException e) {
            throw new LlmClientException("Failed to serialize tool call arguments");
        }
    }

    private String send(String requestBody) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoOutput(true);

            byte[] payload = requestBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int statusCode = connection.getResponseCode();
            boolean success = statusCode >= 200 && statusCode < 300;
            String body = readAll(success ? connection.getInputStream() : connection.getErrorStream());

            if (!success) {
                throw new LlmClientException("LLM provider returned HTTP " + statusCode);
            }
            return body;
        } catch (LlmClientException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmClientException("LLM HTTP request failed: " + e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        try (InputStream in = stream) {
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private LlmResponse parseResponse(String responseBody) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (IOException e) {
            throw new LlmClientException("Failed to parse LLM response");
        }
        if (root == null || root.isNull()) {
            throw new LlmClientException("Empty LLM response");
        }

        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() == 0) {
            throw new LlmClientException("Malformed LLM response: missing choices");
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            throw new LlmClientException("Malformed LLM response: missing message");
        }

        JsonNode toolCallsNode = message.get("tool_calls");
        if (toolCallsNode != null && toolCallsNode.isArray() && toolCallsNode.size() > 0) {
            return parseToolCall(toolCallsNode.get(0));
        }

        JsonNode contentNode = message.get("content");
        if (contentNode == null || contentNode.isNull()) {
            throw new LlmClientException("Malformed LLM response: missing content");
        }
        return LlmResponse.finalAnswer(contentNode.asText());
    }

    private LlmResponse parseToolCall(JsonNode toolCallNode) {
        JsonNode function = toolCallNode.get("function");
        if (function == null) {
            throw new LlmClientException("Malformed LLM response: missing function in tool call");
        }
        String id = toolCallNode.path("id").asText(null);
        String name = function.path("name").asText(null);
        String argumentsJson = function.path("arguments").asText("{}");

        Map<String, Object> arguments;
        try {
            arguments = mapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new LlmClientException("Malformed LLM response: invalid tool call arguments");
        }

        return LlmResponse.toolCall(new ToolCall(id, name, arguments));
    }
}
