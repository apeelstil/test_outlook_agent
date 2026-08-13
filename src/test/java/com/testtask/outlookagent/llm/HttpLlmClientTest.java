package com.testtask.outlookagent.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.testtask.outlookagent.tool.Tool;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * RED spec: describes the expected wire contract for the future OpenAI-compatible
 * HttpLlmClient (Chat Completions style: configurable endpoint/model/apiKey/timeoutMs,
 * tool/function calling), while keeping core Agent/Tool/LlmMessage/LlmResponse/ToolCall
 * provider-neutral. See PLAN.md Roadmap #13 and CLAUDE.md TDD invariants.
 *
 * None of this compiles yet: HttpLlmClient does not exist, ToolCall has no id,
 * LlmMessage.toolResult has no toolCallId overload, and Tool exposes no
 * description/parameters schema. That is the intended RED.
 */
public class HttpLlmClientTest {

    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String API_KEY = "test-api-key-123";
    private static final String MODEL = "test-model-v1";

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private CountDownLatch blockLatch;

    @After
    public void tearDown() {
        if (blockLatch != null) {
            blockLatch.countDown();
        }
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    public void finalResponse_sendsConfiguredRequestAndParsesFinalAnswer() throws IOException {
        String responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello from provider\"}}]}";
        RecordingHandler handler = new RecordingHandler(200, responseBody);
        String endpoint = startServer(handler);

        HttpLlmClient client = new HttpLlmClient(endpoint, MODEL, API_KEY, 5000L);

        LlmResponse response = client.chat(
                Collections.singletonList(LlmMessage.userMessage("Hi there")),
                Collections.<Tool>emptyList());

        assertEquals("POST", handler.method);
        assertEquals(CHAT_PATH, handler.path);
        assertEquals("Bearer " + API_KEY, firstHeader(handler.requestHeaders, "Authorization"));

        JsonNode requestJson = mapper.readTree(handler.requestBody);
        assertEquals(MODEL, requestJson.get("model").asText());
        JsonNode messages = requestJson.get("messages");
        assertTrue(messages.isArray());
        boolean foundUserMessage = false;
        for (JsonNode message : messages) {
            if ("user".equals(message.get("role").asText()) && "Hi there".equals(message.get("content").asText())) {
                foundUserMessage = true;
            }
        }
        assertTrue("request must contain the user message as JSON, not string-concatenated", foundUserMessage);

        assertFalse(response.isToolCall());
        assertEquals("Hello from provider", response.getFinalAnswer());
    }

    @Test
    public void toolCall_parsesProviderNeutralToolCallWithIdNameAndArguments() throws IOException {
        String responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                + "{\"id\":\"call_123\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"add_reminder\","
                + "\"arguments\":\"{\\\"text\\\":\\\"Buy milk\\\",\\\"dueIso\\\":\\\"2026-08-14T10:00:00Z\\\"}\""
                + "}}]}}]}";
        RecordingHandler handler = new RecordingHandler(200, responseBody);
        String endpoint = startServer(handler);

        HttpLlmClient client = new HttpLlmClient(endpoint, MODEL, API_KEY, 5000L);

        LlmResponse response = client.chat(
                Collections.singletonList(LlmMessage.userMessage("please add a reminder")),
                Collections.<Tool>emptyList());

        assertTrue(response.isToolCall());
        ToolCall toolCall = response.getToolCall();

        // Provider-neutral contract requires ToolCall.getId(); current ToolCall has no id field.
        assertEquals("call_123", toolCall.getId());
        assertEquals("add_reminder", toolCall.getToolName());

        // Arguments must already be a parsed Map, never a raw JSON string leaking into Agent.
        Map<String, Object> arguments = toolCall.getArguments();
        assertEquals("Buy milk", arguments.get("text"));
        assertEquals("2026-08-14T10:00:00Z", arguments.get("dueIso"));
    }

    @Test
    public void toolSchemas_sendsProviderNeutralMetadataForConfiguredTools() throws IOException {
        String responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
        RecordingHandler handler = new RecordingHandler(200, responseBody);
        String endpoint = startServer(handler);

        HttpLlmClient client = new HttpLlmClient(endpoint, MODEL, API_KEY, 5000L);

        List<Tool> tools = java.util.Arrays.asList(
                (Tool) new NoArgTool(),
                (Tool) new AddReminderLikeTool(),
                (Tool) new FindItemsLikeTool());

        client.chat(Collections.singletonList(LlmMessage.userMessage("what tools are available?")), tools);

        JsonNode requestJson = mapper.readTree(handler.requestBody);
        JsonNode toolsNode = requestJson.get("tools");
        assertNotNull("request must carry tool metadata, not just names", toolsNode);
        assertTrue(toolsNode.isArray());
        assertEquals(3, toolsNode.size());

        JsonNode currentDatetime = findFunctionByName(toolsNode, "current_datetime");
        assertNotNull(currentDatetime);
        assertFalse(currentDatetime.get("description").asText().isEmpty());
        JsonNode currentDatetimeRequired = currentDatetime.get("parameters").get("required");
        assertTrue(currentDatetimeRequired == null || currentDatetimeRequired.size() == 0);

        JsonNode addReminder = findFunctionByName(toolsNode, "add_reminder");
        assertNotNull(addReminder);
        JsonNode addReminderRequired = addReminder.get("parameters").get("required");
        assertTrue(containsText(addReminderRequired, "text"));
        assertTrue(containsText(addReminderRequired, "dueIso"));
        assertEquals("string", addReminder.get("parameters").get("properties").get("text").get("type").asText());

        JsonNode findItems = findFunctionByName(toolsNode, "find_items");
        assertNotNull(findItems);
        JsonNode findItemsRequired = findItems.get("parameters").get("required");
        assertTrue(containsText(findItemsRequired, "query"));
    }

    @Test
    public void toolResultCorrelation_sendsToolCallIdWithToolResultMessage() throws IOException {
        String responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}";
        RecordingHandler handler = new RecordingHandler(200, responseBody);
        String endpoint = startServer(handler);

        HttpLlmClient client = new HttpLlmClient(endpoint, MODEL, API_KEY, 5000L);

        List<LlmMessage> messages = java.util.Arrays.asList(
                LlmMessage.userMessage("please add a reminder"),
                // Provider-neutral toolResult must be able to carry the originating tool_call id;
                // current LlmMessage.toolResult(String) has no such overload.
                LlmMessage.toolResult("Reminder added", "call_123"));

        client.chat(messages, Collections.<Tool>emptyList());

        JsonNode requestJson = mapper.readTree(handler.requestBody);
        JsonNode messagesNode = requestJson.get("messages");
        boolean foundToolResult = false;
        for (JsonNode message : messagesNode) {
            if ("tool".equals(message.get("role").asText())) {
                assertEquals("call_123", message.get("tool_call_id").asText());
                assertEquals("Reminder added", message.get("content").asText());
                foundToolResult = true;
            }
        }
        assertTrue("request must serialize the tool result with its tool_call_id", foundToolResult);
    }

    @Test
    public void httpFailure_doesNotLeakRawResponseBodyInException() throws IOException {
        String secretBody = "provider-secret-body-123";
        RecordingHandler handler = new RecordingHandler(500, secretBody);
        String endpoint = startServer(handler);

        HttpLlmClient client = new HttpLlmClient(endpoint, MODEL, API_KEY, 5000L);

        try {
            client.chat(Collections.singletonList(LlmMessage.userMessage("hi")), Collections.<Tool>emptyList());
            fail("Expected a controlled runtime failure on HTTP 500");
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            assertFalse("exception message must not leak the raw provider response body",
                    message.contains(secretBody));
        }
    }

    @Test
    public void malformedSuccessResponse_doesNotReturnFakeSuccessOrLeakBody() throws IOException {
        String malformedBody = "{not-json-provider-secret-marker-456";
        RecordingHandler handler = new RecordingHandler(200, malformedBody);
        String endpoint = startServer(handler);

        HttpLlmClient client = new HttpLlmClient(endpoint, MODEL, API_KEY, 5000L);

        try {
            LlmResponse response = client.chat(
                    Collections.singletonList(LlmMessage.userMessage("hi")), Collections.<Tool>emptyList());
            fail("Expected a controlled failure on malformed HTTP 200 body, got: " + response);
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            assertFalse("exception message must not leak the raw provider response body",
                    message.contains("provider-secret-marker-456"));
        }
    }

    @Test
    public void timeout_boundsHttpOperationAndFailsWithoutHanging() throws IOException {
        blockLatch = new CountDownLatch(1);
        RecordingHandler handler = new RecordingHandler(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"too late\"}}]}", blockLatch);
        String endpoint = startServer(handler);

        long configuredTimeoutMs = 300L;
        long serverSideFailSafeBoundMs = 5000L;
        HttpLlmClient client = new HttpLlmClient(endpoint, MODEL, API_KEY, configuredTimeoutMs);

        long start = System.currentTimeMillis();
        try {
            client.chat(Collections.singletonList(LlmMessage.userMessage("hi")), Collections.<Tool>emptyList());
            fail("Expected a timeout failure when the provider never responds in time");
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("client must fail close to its own timeoutMs, not hang until the server unblocks",
                    elapsed < serverSideFailSafeBoundMs);
        }
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

    private static String firstHeader(Headers headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static JsonNode findFunctionByName(JsonNode toolsNode, String name) {
        for (JsonNode toolNode : toolsNode) {
            JsonNode function = toolNode.has("function") ? toolNode.get("function") : toolNode;
            if (function.has("name") && name.equals(function.get("name").asText())) {
                return function;
            }
        }
        return null;
    }

    private static boolean containsText(JsonNode arrayNode, String value) {
        if (arrayNode == null) {
            return false;
        }
        for (JsonNode node : arrayNode) {
            if (value.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    /** Local double: metadata-bearing tool with no required arguments, mirroring current_datetime. */
    private static class NoArgTool implements Tool {
        @Override
        public String getName() {
            return "current_datetime";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return "unused-in-this-test";
        }

        // Not part of the current Tool contract: this is exactly the missing metadata RED-fixture.
        public String getDescription() {
            return "Returns the current date and time.";
        }

        public Map<String, Object> getParametersSchema() {
            return Collections.emptyMap();
        }
    }

    /** Local double mirroring add_reminder: required String text, dueIso. */
    private static class AddReminderLikeTool implements Tool {
        @Override
        public String getName() {
            return "add_reminder";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return "unused-in-this-test";
        }

        public String getDescription() {
            return "Adds a reminder with text and an ISO-8601 due date.";
        }

        public Map<String, Object> getParametersSchema() {
            return Collections.emptyMap();
        }
    }

    /** Local double mirroring find_items: required String query. */
    private static class FindItemsLikeTool implements Tool {
        @Override
        public String getName() {
            return "find_items";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return "unused-in-this-test";
        }

        public String getDescription() {
            return "Finds stored reminders matching a query.";
        }

        public Map<String, Object> getParametersSchema() {
            return Collections.emptyMap();
        }
    }

    private static class RecordingHandler implements HttpHandler {

        private final int statusCode;
        private final String responseBody;
        private final CountDownLatch blockLatch;

        volatile String method;
        volatile String path;
        volatile Headers requestHeaders;
        volatile String requestBody;

        RecordingHandler(int statusCode, String responseBody) {
            this(statusCode, responseBody, null);
        }

        RecordingHandler(int statusCode, String responseBody, CountDownLatch blockLatch) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
            this.blockLatch = blockLatch;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            this.method = exchange.getRequestMethod();
            this.path = exchange.getRequestURI().getPath();
            this.requestHeaders = exchange.getRequestHeaders();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            try (InputStream requestStream = exchange.getRequestBody()) {
                while ((read = requestStream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            }
            this.requestBody = new String(buffer.toByteArray(), StandardCharsets.UTF_8);

            if (blockLatch != null) {
                try {
                    blockLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream responseStream = exchange.getResponseBody()) {
                responseStream.write(responseBytes);
            }
        }
    }
}
