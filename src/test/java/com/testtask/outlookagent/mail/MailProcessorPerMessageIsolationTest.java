package com.testtask.outlookagent.mail;

import static org.junit.Assert.assertTrue;

import com.testtask.outlookagent.agent.Agent;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.store.SeenStore;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * RED spec: MailProcessor.processUnread() iterates the fetched messages in a
 * single for-loop with no per-message try/catch around the
 * seenStore.markSeen(...) step - only mailChannel.reply(...) is guarded there.
 * A RuntimeException raised by markSeen(...) for one message - e.g. the
 * UncheckedIOException FileSeenStore.markSeen(...) throws on a real disk
 * write failure - therefore propagates out of processUnread() and aborts the
 * whole batch, leaving every later message from the same successful
 * fetchUnread() call unprocessed. This test is expected to fail (RED) on the
 * current production code.
 */
public class MailProcessorPerMessageIsolationTest {

    @Test
    public void markSeenFailureForFirstMessageDoesNotStopSecondMessageInSameBatch() {
        Msg msg1 = new Msg("id-1", "sender@example.test", "subject1", "body1");
        Msg msg2 = new Msg("id-2", "sender@example.test", "subject2", "body2");
        RecordingMailChannel mailChannel = new RecordingMailChannel();
        mailChannel.addUnread(msg1);
        mailChannel.addUnread(msg2);
        Agent agent = new Agent(new CountingLlmClient(), new ToolRegistry(), 3);
        MarkSeenFailsForOneIdSeenStore seenStore = new MarkSeenFailsForOneIdSeenStore("id-1");

        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore);

        processor.processUnread();

        assertTrue("Second message must still be delivered a reply despite the first message's markSeen failure",
                mailChannel.repliedTo("id-2"));
        assertTrue("Second message must be marked seen despite the first message's markSeen failure",
                seenStore.isSeen("id-2"));
    }

    private static class CountingLlmClient implements LlmClient {
        private int callCount = 0;

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            callCount++;
            return LlmResponse.finalAnswer("scripted-answer-" + callCount);
        }
    }

    private static class RecordingMailChannel implements MailChannel {
        private final List<Msg> unread = new ArrayList<>();
        private final Set<String> repliedIds = new HashSet<>();

        void addUnread(Msg msg) {
            unread.add(msg);
        }

        @Override
        public List<Msg> fetchUnread() {
            return unread;
        }

        @Override
        public void reply(Msg msg, String body) {
            repliedIds.add(msg.getId());
        }

        boolean repliedTo(String messageId) {
            return repliedIds.contains(messageId);
        }
    }

    private static class MarkSeenFailsForOneIdSeenStore implements SeenStore {
        private final String failingId;
        private final Set<String> seenIds = new HashSet<>();

        MarkSeenFailsForOneIdSeenStore(String failingId) {
            this.failingId = failingId;
        }

        @Override
        public boolean isSeen(String messageId) {
            return seenIds.contains(messageId);
        }

        @Override
        public void markSeen(String messageId) {
            if (messageId.equals(failingId)) {
                throw new RuntimeException("simulated markSeen failure - internal only");
            }
            seenIds.add(messageId);
        }
    }
}
