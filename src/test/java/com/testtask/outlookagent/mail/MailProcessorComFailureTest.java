package com.testtask.outlookagent.mail;

import static org.junit.Assert.assertFalse;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * RED spec (Stage 33): a COM/JACOB failure surfaced through
 * MailChannel.fetchUnread() during one poll cycle must not propagate out of
 * MailProcessor.processUnread() and must not kill the next, separate poll
 * cycle. Currently processUnread() does not catch exceptions thrown by
 * fetchUnread() itself, so this is a runtime (not compile-level) RED: the
 * first processUnread() call is expected to throw, and the WARN
 * event=mail_fetch_failed log line is expected to be absent. See PLAN.md
 * "Архитектурные границы" (poll-loop must not fail on a single COM error)
 * and Тестовое-задание §3.6.
 */
public class MailProcessorComFailureTest {

    private static final String SIMULATED_FAILURE_MARKER = "simulated-com-failure-should-not-appear-in-logs";

    private Logger processorLogger;
    private ListAppender<ILoggingEvent> appender;

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
    public void comFailureOnFetchDoesNotStopFollowingPollCycle() {
        Msg okMsg = new Msg("id-ok-after-failure", "sender2@example.test", "subject2", "body2");
        FlakyThenOkMailChannel mailChannel = new FlakyThenOkMailChannel(Collections.singletonList(okMsg));
        Agent agent = new Agent(new CountingLlmClient(), new ToolRegistry(), 3);
        InMemorySeenStore seenStore = new InMemorySeenStore();
        RecordingAuditJournal auditJournal = new RecordingAuditJournal();

        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore, auditJournal);

        processor.processUnread();

        boolean foundWarnEvent = false;
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            if (message != null) {
                assertFalse("Log must not contain the raw failure detail", message.contains(SIMULATED_FAILURE_MARKER));
            }
            if (event.getLevel() == Level.WARN && message != null && message.contains("mail_fetch_failed")) {
                foundWarnEvent = true;
            }
        }
        assertTrue("Expected a WARN event=mail_fetch_failed log for the failed fetch", foundWarnEvent);
        assertFalse(seenStore.isSeen("id-ok-after-failure"));

        processor.processUnread();

        assertTrue("Second, separate poll cycle must process a normal message",
                seenStore.isSeen("id-ok-after-failure"));
    }

    private static class FlakyThenOkMailChannel implements MailChannel {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final List<Msg> secondCallMessages;

        FlakyThenOkMailChannel(List<Msg> secondCallMessages) {
            this.secondCallMessages = secondCallMessages;
        }

        @Override
        public List<Msg> fetchUnread() {
            if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException(SIMULATED_FAILURE_MARKER);
            }
            return secondCallMessages;
        }

        @Override
        public void reply(Msg msg, String body) {
        }
    }

    private static class CountingLlmClient implements LlmClient {
        private int callCount = 0;

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            callCount++;
            return LlmResponse.finalAnswer("scripted-answer-" + callCount);
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
    }
}
