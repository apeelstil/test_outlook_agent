package com.testtask.outlookagent.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.testtask.outlookagent.agent.Agent;
import com.testtask.outlookagent.audit.AuditEntry;
import com.testtask.outlookagent.audit.AuditEvent;
import com.testtask.outlookagent.audit.AuditJournal;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.store.SeenStore;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * RED spec (Stage 31): structured SLF4J/logback logging and audit journal
 * integration for MailProcessor.processUnread(). Requires a new
 * MailProcessor(MailChannel, Agent, SeenStore, AuditJournal) constructor and
 * the com.testtask.outlookagent.audit package, none of which exist yet -
 * compile-level RED (missing production audit API and missing slf4j/logback
 * dependency). See PLAN.md "Security / audit" and Тестовое-задание
 * §3.6/§9/§11.
 */
public class MailProcessorAuditLoggingTest {

    private static final String SECRET_SENDER = "sensitive-sender@example.test";
    private static final String SECRET_SUBJECT = "sensitive subject marker";
    private static final String SECRET_BODY = "sensitive body marker";
    private static final String RAW_MESSAGE_ID = "raw-entryid-should-not-appear";

    private ListAppender<ILoggingEvent> appender;
    private Logger processorLogger;

    @Before
    public void attachAppender() {
        processorLogger = (Logger) LoggerFactory.getLogger(MailProcessor.class);
        appender = new ListAppender<>();
        appender.start();
        processorLogger.addAppender(appender);
    }

    @After
    public void detachAppender() {
        processorLogger.detachAppender(appender);
    }

    @Test
    public void successfullyProcessedMailLogsStructuredEventWithoutPii() {
        InMemoryMailChannel mailChannel = new InMemoryMailChannel();
        mailChannel.addUnread(new Msg(RAW_MESSAGE_ID, SECRET_SENDER, SECRET_SUBJECT, SECRET_BODY));
        Agent agent = new Agent(new CountingLlmClient(), new ToolRegistry(), 3);
        InMemorySeenStore seenStore = new InMemorySeenStore();
        RecordingAuditJournal auditJournal = new RecordingAuditJournal();

        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore, auditJournal);
        processor.processUnread();

        boolean foundMailSeenEvent = false;
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertNotSensitive(message);
            if (message != null && message.contains("agent_mail_seen")) {
                foundMailSeenEvent = true;
            }
        }
        assertTrue("Expected agent_mail_seen structured log for successfully processed mail", foundMailSeenEvent);
    }

    @Test
    public void successfullyProcessedMailAppendsAuditEventWithHashedReferenceOnly() {
        InMemoryMailChannel mailChannel = new InMemoryMailChannel();
        mailChannel.addUnread(new Msg(RAW_MESSAGE_ID, SECRET_SENDER, SECRET_SUBJECT, SECRET_BODY));
        Agent agent = new Agent(new CountingLlmClient(), new ToolRegistry(), 3);
        InMemorySeenStore seenStore = new InMemorySeenStore();
        RecordingAuditJournal auditJournal = new RecordingAuditJournal();

        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore, auditJournal);
        processor.processUnread();

        List<AuditEvent> mailEvents = auditJournal.eventsWithKey("agent_mail_seen");
        assertEquals(1, mailEvents.size());
        AuditEvent mailEvent = mailEvents.get(0);

        assertNotNull("Mail audit event must carry a hashed message reference", mailEvent.getHashedMessageRef());
        assertNotEquals("Hashed reference must not equal the raw message id",
                RAW_MESSAGE_ID, mailEvent.getHashedMessageRef());
        assertFalse("Hashed reference must not contain the raw message id as a substring",
                mailEvent.getHashedMessageRef().contains(RAW_MESSAGE_ID));
        assertFalse(auditJournal.anyEventFieldContains(SECRET_SENDER));
        assertFalse(auditJournal.anyEventFieldContains(SECRET_SUBJECT));
        assertFalse(auditJournal.anyEventFieldContains(SECRET_BODY));
    }

    @Test
    public void failedReplyLogsWarnEventAndDoesNotStopProcessingNextMessage() {
        SelectiveFailureMailChannel mailChannel = new SelectiveFailureMailChannel(RAW_MESSAGE_ID);
        mailChannel.addUnread(new Msg(RAW_MESSAGE_ID, SECRET_SENDER, SECRET_SUBJECT, SECRET_BODY));
        mailChannel.addUnread(new Msg("id-ok", "sender2@example.test", "subject2", "body2"));
        Agent agent = new Agent(new CountingLlmClient(), new ToolRegistry(), 3);
        InMemorySeenStore seenStore = new InMemorySeenStore();
        RecordingAuditJournal auditJournal = new RecordingAuditJournal();

        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore, auditJournal);
        processor.processUnread();

        assertTrue(seenStore.isSeen("id-ok"));
        assertFalse(seenStore.isSeen(RAW_MESSAGE_ID));

        boolean foundWarnEvent = false;
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertNotSensitive(message);
            if (event.getLevel() == Level.WARN) {
                foundWarnEvent = true;
            }
        }
        assertTrue("Expected a WARN log event for the failed reply", foundWarnEvent);
    }

    private void assertNotSensitive(String message) {
        if (message == null) {
            return;
        }
        assertFalse(message.contains(SECRET_SENDER));
        assertFalse(message.contains(SECRET_SUBJECT));
        assertFalse(message.contains(SECRET_BODY));
        assertFalse(message.contains(RAW_MESSAGE_ID));
    }

    private static class CountingLlmClient implements LlmClient {
        private int callCount = 0;

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            callCount++;
            return LlmResponse.finalAnswer("scripted-answer-" + callCount);
        }
    }

    private static class InMemoryMailChannel implements MailChannel {
        private final List<Msg> unread = new ArrayList<>();

        void addUnread(Msg msg) {
            unread.add(msg);
        }

        @Override
        public List<Msg> fetchUnread() {
            return unread;
        }

        @Override
        public void reply(Msg msg, String body) {
        }
    }

    private static class SelectiveFailureMailChannel implements MailChannel {
        private final List<Msg> unread = new ArrayList<>();
        private final Set<String> failingIds;

        SelectiveFailureMailChannel(String... failingIds) {
            this.failingIds = new HashSet<>(Arrays.asList(failingIds));
        }

        void addUnread(Msg msg) {
            unread.add(msg);
        }

        @Override
        public List<Msg> fetchUnread() {
            return unread;
        }

        @Override
        public void reply(Msg msg, String body) {
            if (failingIds.contains(msg.getId())) {
                throw new RuntimeException("simulated reply failure - internal only");
            }
        }
    }

    private static class InMemorySeenStore implements SeenStore {
        private final Set<String> seenIds = new HashSet<>();

        @Override
        public boolean isSeen(String messageId) {
            return seenIds.contains(messageId);
        }

        @Override
        public void markSeen(String messageId) {
            seenIds.add(messageId);
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
