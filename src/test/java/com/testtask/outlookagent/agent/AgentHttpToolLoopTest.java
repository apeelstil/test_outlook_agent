package com.testtask.outlookagent.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.testtask.outlookagent.llm.HttpLlmClient;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Test;

/**
 * RED spec: full Agent + HttpLlmClient + local HttpServer round-trip. Verifies that the second
 * Chat Completions request carries provider-neutral conversation history in the order required
 * by the official OpenAI contract: user message, then the assistant message that originated the
 * tool_calls (with the same id/name/arguments the provider sent), then the tool message with a
 * matching tool_call_id. See platform.openai.com/docs/guides/function-calling and the cookbook
 * "How_to_call_functions_with_chat_models": a role="tool" message must be a response to a
 * preceding message with tool_calls.
 *
 * Expected RED cause: LlmMessage has no factory to represent an assistant tool-call message,
 * Agent.run never appends one to the conversation, and HttpLlmClient.buildRequestBody has no
 * branch to serialize role="assistant" with tool_calls. So the second request's "messages" array
 * never contains the assistant tool-call turn at all.
 */
public class AgentHttpToolLoopTest {

    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String TOOL_CALL_ID = "call_123";
    private static final String TOOL_NAME = "get_status";
    private static final String USER_MESSAGE = "please check status";
    private static final String FINAL_ANSWER = "Status checked: all good";

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private ExecutorService serverExecutor;

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    public void secondRequest_containsAssistantToolCallContextBeforeToolResult() throws IOException {
        String firstResponseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                + "{\"id\":\"" + TOOL_CALL_ID + "\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"" + TOOL_NAME + "\",\"arguments\":\"{}\"}}]}}]}";
        String secondResponseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + FINAL_ANSWER + "\"}}]}";

        RecordingHandler handler = new RecordingHandler(firstResponseBody, secondResponseBody);
        String endpoint = startServer(handler);

        HttpLlmClient llmClient = new HttpLlmClient(endpoint, "test-model-v1", "test-api-key", 5000L);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new GetStatusTool());

        Agent agent = new Agent(llmClient, registry, 3);

        String result = agent.run(USER_MESSAGE);

        assertEquals(FINAL_ANSWER, result);
        assertEquals("expected exactly two HTTP requests: initial + post-tool-call", 2, handler.requestBodies.size());

        JsonNode secondRequestJson = mapper.readTree(handler.requestBodies.get(1));
        JsonNode messages = secondRequestJson.get("messages");
        assertNotNull("second request must carry a messages array", messages);
        assertTrue(messages.isArray());

        int userIndex = -1;
        int assistantToolCallIndex = -1;
        int toolResultIndex = -1;

        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            String role = message.get("role") == null ? null : message.get("role").asText();

            if ("user".equals(role) && USER_MESSAGE.equals(message.get("content").asText())) {
                userIndex = i;
            }

            if ("assistant".equals(role) && message.has("tool_calls") && message.get("tool_calls").isArray()
                    && message.get("tool_calls").size() > 0) {
                JsonNode toolCallNode = message.get("tool_calls").get(0);
                JsonNode function = toolCallNode.get("function");
                if (function != null
                        && TOOL_CALL_ID.equals(toolCallNode.path("id").asText(null))
                        && TOOL_NAME.equals(function.path("name").asText(null))) {
                    assistantToolCallIndex = i;
                }
            }

            if ("tool".equals(role) && TOOL_CALL_ID.equals(
                    message.get("tool_call_id") == null ? null : message.get("tool_call_id").asText())) {
                toolResultIndex = i;
            }
        }

        assertTrue("second request must contain the original user message", userIndex >= 0);
        assertTrue("second request must contain the assistant message with the original tool_calls "
                        + "(id=" + TOOL_CALL_ID + ", function=" + TOOL_NAME + ") before the tool result",
                assistantToolCallIndex >= 0);
        assertTrue("second request must contain a tool message correlated via tool_call_id="
                        + TOOL_CALL_ID,
                toolResultIndex >= 0);

        assertTrue("assistant tool-call message must come after the user message",
                userIndex < assistantToolCallIndex);
        assertTrue("tool result message must come after the assistant tool-call message that originated it",
                assistantToolCallIndex < toolResultIndex);
    }

    private String startServer(HttpHandler handler) throws IOException {
        HttpServer newServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        newServer.createContext(CHAT_PATH, handler);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        newServer.setExecutor(executor);
        newServer.start();
        this.server = newServer;
        this.serverExecutor = executor;
        return "http://127.0.0.1:" + newServer.getAddress().getPort() + CHAT_PATH;
    }

    private static class GetStatusTool implements Tool {
        @Override
        public String getName() {
            return TOOL_NAME;
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return "status:ok";
        }
    }

    /** Serves scripted response bodies in order, one per incoming request, recording each request body. */
    private static class RecordingHandler implements HttpHandler {

        private final List<String> scriptedResponses;
        private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<String>());
        private int callIndex = 0;

        RecordingHandler(String... scriptedResponses) {
            this.scriptedResponses = java.util.Arrays.asList(scriptedResponses);
        }

        @Override
        public synchronized void handle(HttpExchange exchange) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            try (InputStream requestStream = exchange.getRequestBody()) {
                while ((read = requestStream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            }
            requestBodies.add(new String(buffer.toByteArray(), StandardCharsets.UTF_8));

            String responseBody = scriptedResponses.get(callIndex);
            callIndex++;

            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream responseStream = exchange.getResponseBody()) {
                responseStream.write(responseBytes);
            }
        }
    }
}
