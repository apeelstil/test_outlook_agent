package com.testtask.outlookagent.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.testtask.outlookagent.agent.Agent;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.llm.ToolCall;
import com.testtask.outlookagent.store.FileReminderStore;
import com.testtask.outlookagent.store.FileSeenStore;
import com.testtask.outlookagent.store.SeenStore;
import com.testtask.outlookagent.tool.AddReminderTool;
import com.testtask.outlookagent.tool.Tool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RED spec: an unread message whose Agent.run() already executed a
 * side-effecting tool (add_reminder) must not re-execute that tool just
 * because MailChannel.reply() failed and the message was retried on a later
 * processing cycle (same process) or after a process restart. See PLAN.md
 * "Идемпотентность" and Тестовое-задание §3.6/§9: a stable Msg.id must be
 * processed exactly once end-to-end, including any side-effecting tool calls
 * made along the way - reply failure must not cause tool replay.
 *
 * Current MailProcessor/Agent only guard mail_reply + markSeen exactly-once;
 * Agent.run() re-derives the tool call from scratch on every invocation
 * because it starts from an empty message list each time, so a failed-reply
 * retry re-executes add_reminder. This test is expected to fail (RED) on the
 * current production code.
 */
public class MailProcessorReplaySafetyTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void failedReplyRetryInSameProcessDoesNotReplaySideEffectingTool() {
        Path remindersPath = remindersFilePath();
        FileReminderStore reminderStore = new FileReminderStore(remindersPath);
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new AddReminderTool(reminderStore));

        FlakyReplyMailChannel mailChannel = new FlakyReplyMailChannel();
        mailChannel.addUnread(new Msg("id-1", "sender@example.test", "subject", "body"));
        ScriptedAddReminderLlmClient llmClient = new ScriptedAddReminderLlmClient();
        Agent agent = new Agent(llmClient, toolRegistry, 3);
        SeenStore seenStore = new FileSeenStore(seenFilePath());
        MailProcessor processor = new MailProcessor(mailChannel, agent, seenStore);

        processor.processUnread();

        assertEquals(1, reminderStore.find("sample contact").size());
        assertEquals(1, mailChannel.replyAttemptCount("id-1"));
        assertFalse(seenStore.isSeen("id-1"));

        processor.processUnread();

        assertEquals(2, mailChannel.replyAttemptCount("id-1"));
        assertTrue(seenStore.isSeen("id-1"));
        assertEquals("Reminder scheduled.", mailChannel.getLastReplyBody("id-1"));
        assertEquals("add_reminder must not have executed a second time for the same Msg.id",
                1, reminderStore.find("sample contact").size());
    }

    @Test
    public void failedReplyRetryAfterProcessRestartDoesNotReplaySideEffectingTool() {
        Path remindersPath = remindersFilePath();
        Path seenPath = seenFilePath();
        FlakyReplyMailChannel mailChannel = new FlakyReplyMailChannel();
        mailChannel.addUnread(new Msg("id-1", "sender@example.test", "subject", "body"));

        FileReminderStore firstReminderStore = new FileReminderStore(remindersPath);
        ToolRegistry firstToolRegistry = new ToolRegistry();
        firstToolRegistry.register(new AddReminderTool(firstReminderStore));
        Agent firstAgent = new Agent(new ScriptedAddReminderLlmClient(), firstToolRegistry, 3);
        SeenStore firstSeenStore = new FileSeenStore(seenPath);
        MailProcessor firstProcessor = new MailProcessor(mailChannel, firstAgent, firstSeenStore);

        firstProcessor.processUnread();

        assertEquals(1, firstReminderStore.find("sample contact").size());
        assertFalse(firstSeenStore.isSeen("id-1"));

        FileReminderStore secondReminderStore = new FileReminderStore(remindersPath);
        ToolRegistry secondToolRegistry = new ToolRegistry();
        secondToolRegistry.register(new AddReminderTool(secondReminderStore));
        Agent secondAgent = new Agent(new ScriptedAddReminderLlmClient(), secondToolRegistry, 3);
        SeenStore secondSeenStore = new FileSeenStore(seenPath);
        MailProcessor secondProcessor = new MailProcessor(mailChannel, secondAgent, secondSeenStore);

        secondProcessor.processUnread();

        assertEquals(2, mailChannel.replyAttemptCount("id-1"));
        assertTrue(secondSeenStore.isSeen("id-1"));
        assertEquals("add_reminder must not have executed a second time after restart for the same Msg.id",
                1, secondReminderStore.find("sample contact").size());
    }

    private Path remindersFilePath() {
        return new File(temporaryFolder.getRoot(), "reminders.json").toPath();
    }

    private Path seenFilePath() {
        return new File(temporaryFolder.getRoot(), "seen.json").toPath();
    }

    /**
     * Models a deterministic LLM that, given a fresh (single user-message)
     * conversation, always decides to call add_reminder first and only
     * returns a final answer once the tool result is already in context -
     * i.e. it has no memory of previous, separate Agent.run() invocations.
     */
    private static class ScriptedAddReminderLlmClient implements LlmClient {

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            if (messages.size() <= 1) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("text", "call sample contact");
                args.put("dueIso", "2026-08-14T07:00:00Z");
                return LlmResponse.toolCall(new ToolCall("call-1", "add_reminder", args));
            }
            return LlmResponse.finalAnswer("Reminder scheduled.");
        }
    }

    /**
     * Keeps the message permanently unread (as a real mailbox would while it
     * remains unanswered) and fails the first reply attempt per message id,
     * succeeding on any subsequent attempt.
     */
    private static class FlakyReplyMailChannel implements MailChannel {

        private final List<Msg> unread = new ArrayList<>();
        private final Map<String, Integer> attemptsByMsgId = new LinkedHashMap<>();
        private final Map<String, String> lastReplyBodyByMsgId = new LinkedHashMap<>();

        void addUnread(Msg msg) {
            unread.add(msg);
        }

        @Override
        public List<Msg> fetchUnread() {
            return unread;
        }

        @Override
        public void reply(Msg msg, String body) {
            int attempt = attemptsByMsgId.getOrDefault(msg.getId(), 0) + 1;
            attemptsByMsgId.put(msg.getId(), attempt);
            if (attempt == 1) {
                throw new RuntimeException("simulated reply failure - internal only");
            }
            lastReplyBodyByMsgId.put(msg.getId(), body);
        }

        int replyAttemptCount(String messageId) {
            return attemptsByMsgId.getOrDefault(messageId, 0);
        }

        String getLastReplyBody(String messageId) {
            return lastReplyBodyByMsgId.get(messageId);
        }
    }
}
