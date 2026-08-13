package com.testtask.outlookagent.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.testtask.outlookagent.audit.AuditEntry;
import com.testtask.outlookagent.audit.AuditEvent;
import com.testtask.outlookagent.audit.AuditJournal;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.llm.ToolCall;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * RED spec (Stage 31): structured SLF4J/logback logging and audit journal
 * integration for Agent.run(). Requires a new Agent(LlmClient, ToolRegistry,
 * int, AuditJournal) constructor and the com.testtask.outlookagent.audit
 * package, none of which exist yet - compile-level RED (missing production
 * audit API and missing slf4j/logback dependency). See PLAN.md "Security /
 * audit" and Тестовое-задание §3.6/§9/§11.
 */
public class AgentAuditLoggingTest {

    private static final String SECRET_ARG_VALUE = "super-secret-arg-value-42";
    private static final String SECRET_EXCEPTION_MARKER = "internal-llm-secret-marker";
    private static final String SECRET_USER_MESSAGE = "private-user-message-marker";

    private ListAppender<ILoggingEvent> appender;
    private Logger agentLogger;

    @Before
    public void attachAppender() {
        agentLogger = (Logger) LoggerFactory.getLogger(Agent.class);
        appender = new ListAppender<>();
        appender.start();
        agentLogger.addAppender(appender);
    }

    @After
    public void detachAppender() {
        agentLogger.detachAppender(appender);
    }

    @Test
    public void toolCallLogsStructuredEventKeyAndToolNameWithoutArgumentValues() {
        Map<String, Object> args = new HashMap<>();
        args.put("text", SECRET_ARG_VALUE);
        ToolCall toolCall = new ToolCall("echo", args);
        EchoTool echoTool = new EchoTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);

        ScriptedLlmClient llmClient = new ScriptedLlmClient(
                LlmResponse.toolCall(toolCall),
                LlmResponse.finalAnswer("done"));

        RecordingAuditJournal auditJournal = new RecordingAuditJournal();
        Agent agent = new Agent(llmClient, registry, 3, auditJournal);

        agent.run("please echo something");

        boolean foundToolCallEvent = false;
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            if (message == null) {
                continue;
            }
            if (message.contains("agent_tool_call") && message.contains("echo")) {
                foundToolCallEvent = true;
            }
            assertFalse("Tool call log must not contain raw argument value",
                    message.contains(SECRET_ARG_VALUE));
        }
        assertTrue("Expected an agent_tool_call structured log event naming the tool", foundToolCallEvent);
    }

    @Test
    public void toolCallAppendsAuditEventWithToolNameAndNoArguments() {
        Map<String, Object> args = new HashMap<>();
        args.put("text", SECRET_ARG_VALUE);
        ToolCall toolCall = new ToolCall("echo", args);
        EchoTool echoTool = new EchoTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);

        ScriptedLlmClient llmClient = new ScriptedLlmClient(
                LlmResponse.toolCall(toolCall),
                LlmResponse.finalAnswer("done"));

        RecordingAuditJournal auditJournal = new RecordingAuditJournal();
        Agent agent = new Agent(llmClient, registry, 3, auditJournal);

        agent.run("please echo something");

        List<AuditEvent> toolEvents = auditJournal.eventsWithKey("agent_tool_call");
        assertEquals(1, toolEvents.size());
        assertEquals("echo", toolEvents.get(0).getToolName());
        assertFalse("Audit event must not carry raw tool argument values",
                auditJournal.anyEventFieldContains(SECRET_ARG_VALUE));
    }

    @Test
    public void llmFailureLogsWarnEventWithoutLeakingExceptionMessageOrUserMessage() {
        FailingLlmClient llmClient = new FailingLlmClient(SECRET_EXCEPTION_MARKER);
        ToolRegistry registry = new ToolRegistry();
        RecordingAuditJournal auditJournal = new RecordingAuditJournal();
        Agent agent = new Agent(llmClient, registry, 3, auditJournal);

        agent.run(SECRET_USER_MESSAGE);

        boolean foundWarnEvent = false;
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            if (message != null && message.contains("llm_failed")) {
                foundWarnEvent = true;
                assertEquals(Level.WARN, event.getLevel());
            }
            if (message != null) {
                assertFalse("Log must not leak the raw exception message",
                        message.contains(SECRET_EXCEPTION_MARKER));
                assertFalse("Log must not leak the user message",
                        message.contains(SECRET_USER_MESSAGE));
            }
        }
        assertTrue("Expected a WARN llm_failed structured log event", foundWarnEvent);
    }

    private static class EchoTool implements Tool {
        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return "echo:" + args.get("text");
        }
    }

    private static class ScriptedLlmClient implements LlmClient {
        private final LlmResponse[] responses;
        private int callIndex = 0;

        ScriptedLlmClient(LlmResponse... responses) {
            this.responses = responses;
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            return responses[callIndex++];
        }
    }

    private static class FailingLlmClient implements LlmClient {
        private final String failureMessage;

        FailingLlmClient(String failureMessage) {
            this.failureMessage = failureMessage;
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            throw new RuntimeException(failureMessage);
        }
    }

    private static class RecordingAuditJournal implements AuditJournal {
        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void append(AuditEvent event) {
            events.add(event);
        }

        @Override
        public List<AuditEntry> readAll() {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public boolean verifyChainIntegrity() {
            throw new UnsupportedOperationException("not needed for this test");
        }

        List<AuditEvent> eventsWithKey(String key) {
            List<AuditEvent> matches = new ArrayList<>();
            for (AuditEvent event : events) {
                if (key.equals(event.getEventKey())) {
                    matches.add(event);
                }
            }
            return matches;
        }

        boolean anyEventFieldContains(String marker) {
            for (AuditEvent event : events) {
                if (containsMarker(event.getEventKey(), marker)
                        || containsMarker(event.getHashedMessageRef(), marker)
                        || containsMarker(event.getToolName(), marker)) {
                    return true;
                }
            }
            return false;
        }

        private boolean containsMarker(String field, String marker) {
            return field != null && field.contains(marker);
        }
    }
}
