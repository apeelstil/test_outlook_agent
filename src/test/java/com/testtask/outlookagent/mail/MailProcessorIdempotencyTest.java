package com.testtask.outlookagent.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.testtask.outlookagent.agent.Agent;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.store.FileSeenStore;
import com.testtask.outlookagent.store.SeenStore;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RED spec (Roadmap #9): describes the expected mail orchestration/idempotency
 * contract of MailProcessor before any production code exists. See PLAN.md
 * "Идемпотентность" and Тестовое-задание §3.6/§9: fetchUnread -> seen check ->
 * Agent.run(body) -> MailChannel.reply -> durable markSeen, exactly-once per
 * stable Msg.id, dedup key is never subject/body.
 */
public class MailProcessorIdempotencyTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void happyPathRepliesThenMarksMessageSeen() {
        CountingMailChannel mailChannel = new CountingMailChannel();
        mailChannel.addUnread(new Msg("id-1", "sender@example.test", "subject", "body"));
        CountingLlmClient llmClient = new CountingLlmClient();
        Agent agent = new Agent(llmClient, new ToolRegistry(), 3);
        SeenStore seenStore = new FileSeenStore(seenFilePath());

        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore);
        processor.processUnread();

        assertEquals(1, llmClient.callCount());
        assertEquals(1, mailChannel.replyCount("id-1"));
        assertTrue(seenStore.isSeen("id-1"));
    }

    @Test
    public void duplicateProcessUnreadInSameProcessSkipsAlreadySeenMessage() {
        CountingMailChannel mailChannel = new CountingMailChannel();
        mailChannel.addUnread(new Msg("id-1", "sender@example.test", "subject", "body"));
        CountingLlmClient llmClient = new CountingLlmClient();
        Agent agent = new Agent(llmClient, new ToolRegistry(), 3);
        SeenStore seenStore = new FileSeenStore(seenFilePath());
        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore);

        processor.processUnread();
        processor.processUnread();

        assertEquals(1, llmClient.callCount());
        assertEquals(1, mailChannel.replyCount("id-1"));
    }

    @Test
    public void duplicateAfterNewSeenStoreAndProcessorInstancesSkipsAlreadySeenMessage() {
        java.nio.file.Path path = seenFilePath();
        CountingMailChannel mailChannel = new CountingMailChannel();
        mailChannel.addUnread(new Msg("id-1", "sender@example.test", "subject", "body"));

        CountingLlmClient firstLlmClient = new CountingLlmClient();
        Agent firstAgent = new Agent(firstLlmClient, new ToolRegistry(), 3);
        MailProcessor firstProcessor = new MailProcessor(mailChannel, firstAgent, new FileSeenStore(path));
        firstProcessor.processUnread();

        CountingLlmClient secondLlmClient = new CountingLlmClient();
        Agent secondAgent = new Agent(secondLlmClient, new ToolRegistry(), 3);
        MailProcessor secondProcessor = new MailProcessor(mailChannel, secondAgent, new FileSeenStore(path));
        secondProcessor.processUnread();

        assertEquals(0, secondLlmClient.callCount());
        assertEquals(1, mailChannel.replyCount("id-1"));
    }

    @Test
    public void sameSubjectAndBodyWithDifferentIdsAreBothProcessed() {
        CountingMailChannel mailChannel = new CountingMailChannel();
        mailChannel.addUnread(new Msg("id-1", "sender@example.test", "same subject", "same body"));
        mailChannel.addUnread(new Msg("id-2", "sender@example.test", "same subject", "same body"));
        CountingLlmClient llmClient = new CountingLlmClient();
        Agent agent = new Agent(llmClient, new ToolRegistry(), 3);
        SeenStore seenStore = new FileSeenStore(seenFilePath());
        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore);

        processor.processUnread();

        assertEquals(2, llmClient.callCount());
        assertTrue(seenStore.isSeen("id-1"));
        assertTrue(seenStore.isSeen("id-2"));
    }

    @Test
    public void sameIdWithChangedSubjectAndBodyIsTreatedAsAlreadyProcessed() {
        CountingMailChannel mailChannel = new CountingMailChannel();
        mailChannel.addUnread(new Msg("id-1", "sender@example.test", "original subject", "original body"));
        CountingLlmClient llmClient = new CountingLlmClient();
        Agent agent = new Agent(llmClient, new ToolRegistry(), 3);
        SeenStore seenStore = new FileSeenStore(seenFilePath());
        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore);
        processor.processUnread();

        mailChannel.addUnread(new Msg("id-1", "sender@example.test", "changed subject", "changed body"));
        processor.processUnread();

        assertEquals(1, llmClient.callCount());
    }

    @Test
    public void failedReplyForOneMessageDoesNotStopProcessingOfSubsequentMessage() {
        SelectiveFailureMailChannel mailChannel = new SelectiveFailureMailChannel("id-fail");
        mailChannel.addUnread(new Msg("id-fail", "sender@example.test", "subject", "body"));
        mailChannel.addUnread(new Msg("id-ok", "sender@example.test", "subject", "body"));
        CountingLlmClient llmClient = new CountingLlmClient();
        Agent agent = new Agent(llmClient, new ToolRegistry(), 3);
        SeenStore seenStore = new FileSeenStore(seenFilePath());
        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore);

        processor.processUnread();

        assertFalse(seenStore.isSeen("id-fail"));
        assertTrue(seenStore.isSeen("id-ok"));
        assertEquals(1, mailChannel.replyAttemptCount("id-ok"));
        assertEquals(1, mailChannel.replyAttemptCount("id-fail"));
        assertEquals(2, llmClient.callCount());
    }

    @Test
    public void replyHappensBeforeMarkSeen() {
        OrderRecordingChannelAndSeenStore recorder = new OrderRecordingChannelAndSeenStore();
        recorder.addUnread(new Msg("id-1", "sender@example.test", "subject", "body"));
        CountingLlmClient llmClient = new CountingLlmClient();
        Agent agent = new Agent(llmClient, new ToolRegistry(), 3);
        MailProcessor processor = new MailProcessor(recorder, agent, recorder);

        processor.processUnread();

        assertEquals(java.util.Arrays.asList("reply", "markSeen"), recorder.callOrder());
    }

    private java.nio.file.Path seenFilePath() {
        return new File(temporaryFolder.getRoot(), "seen.json").toPath();
    }

    private static class CountingLlmClient implements LlmClient {

        private int callCount = 0;

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            callCount++;
            return LlmResponse.finalAnswer("scripted-answer-" + callCount);
        }

        int callCount() {
            return callCount;
        }
    }

    private static class CountingMailChannel implements MailChannel {

        private final List<Msg> unread = new ArrayList<>();
        private final Map<String, Integer> replyCountByMsgId = new HashMap<>();

        void addUnread(Msg msg) {
            unread.add(msg);
        }

        @Override
        public List<Msg> fetchUnread() {
            return unread;
        }

        @Override
        public void reply(Msg msg, String body) {
            Integer current = replyCountByMsgId.get(msg.getId());
            replyCountByMsgId.put(msg.getId(), current == null ? 1 : current + 1);
        }

        int replyCount(String messageId) {
            Integer count = replyCountByMsgId.get(messageId);
            return count == null ? 0 : count;
        }
    }

    private static class SelectiveFailureMailChannel implements MailChannel {

        private final List<Msg> unread = new ArrayList<>();
        private final Set<String> failingIds;
        private final Map<String, Integer> replyAttemptCountByMsgId = new HashMap<>();

        SelectiveFailureMailChannel(String... failingIds) {
            this.failingIds = new HashSet<>(java.util.Arrays.asList(failingIds));
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
            Integer current = replyAttemptCountByMsgId.get(msg.getId());
            replyAttemptCountByMsgId.put(msg.getId(), current == null ? 1 : current + 1);
            if (failingIds.contains(msg.getId())) {
                throw new RuntimeException("simulated reply failure - internal only");
            }
        }

        int replyAttemptCount(String messageId) {
            Integer count = replyAttemptCountByMsgId.get(messageId);
            return count == null ? 0 : count;
        }
    }

    private static class OrderRecordingChannelAndSeenStore implements MailChannel, SeenStore {

        private final List<Msg> unread = new ArrayList<>();
        private final List<String> callOrder = new ArrayList<>();
        private final Set<String> seenIds = new HashSet<>();

        void addUnread(Msg msg) {
            unread.add(msg);
        }

        @Override
        public List<Msg> fetchUnread() {
            return unread;
        }

        @Override
        public void reply(Msg msg, String body) {
            callOrder.add("reply");
        }

        @Override
        public boolean isSeen(String messageId) {
            return seenIds.contains(messageId);
        }

        @Override
        public void markSeen(String messageId) {
            callOrder.add("markSeen");
            seenIds.add(messageId);
        }

        List<String> callOrder() {
            return callOrder;
        }
    }
}
