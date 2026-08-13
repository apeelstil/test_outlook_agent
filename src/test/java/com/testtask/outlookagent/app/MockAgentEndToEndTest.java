package com.testtask.outlookagent.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.testtask.outlookagent.config.AppConfig;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmMessage;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.llm.ToolCall;
import com.testtask.outlookagent.mail.MockMailChannel;
import com.testtask.outlookagent.mail.Msg;
import com.testtask.outlookagent.store.FileReminderStore;
import com.testtask.outlookagent.store.FileSeenStore;
import com.testtask.outlookagent.store.Reminder;
import com.testtask.outlookagent.tool.Tool;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RED spec (Этап 35): full mock end-to-end flow — MockMailChannel -> MailProcessor
 * -> real Agent -> deterministic scripted LlmClient -> real Tools/stores -> reply.
 * Covers the 4 golden scenarios from Тестовое-задание §10. Depends on the
 * not-yet-existing Application/ApplicationFactory composition root (see PLAN.md
 * Roadmap #12).
 */
public class MockAgentEndToEndTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void reminderScenarioSavesReminderAndRepliesOnceThenSkipsOnReprocess() {
        Path seenPath = tempFolder.getRoot().toPath().resolve("seen.json");
        Path reminderPath = tempFolder.getRoot().toPath().resolve("reminders.json");
        Path auditPath = tempFolder.getRoot().toPath().resolve("audit.log");

        MockMailChannel mailChannel = new MockMailChannel();
        Msg msg = new Msg("msg-1", "ivan@example.invalid", "Напоминание", "Напомни завтра в 10 позвонить Ивану");
        mailChannel.addUnread(msg);

        Map<String, Object> addReminderArgs = new HashMap<>();
        addReminderArgs.put("text", "Позвонить Ивану");
        addReminderArgs.put("dueIso", "2026-08-14T10:00:00Z");
        ScriptedLlmClient llmClient = new ScriptedLlmClient(
                LlmResponse.toolCall(new ToolCall("call-1", "add_reminder", addReminderArgs)),
                LlmResponse.finalAnswer("Хорошо, напомню завтра в 10 позвонить Ивану."));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);

        Application application = ApplicationFactory.create(
                minimalConfig(3), mailChannel, llmClient, fixedClock, seenPath, reminderPath, auditPath);

        application.getMailProcessor().processUnread();

        assertEquals("Хорошо, напомню завтра в 10 позвонить Ивану.", mailChannel.getReplyBody("msg-1"));
        assertTrue("Message must be marked seen after a successful reply",
                new FileSeenStore(seenPath).isSeen("msg-1"));

        List<Reminder> saved = new FileReminderStore(reminderPath).find("Иван");
        assertEquals(1, saved.size());
        assertEquals("Позвонить Ивану", saved.get(0).getText());
        assertEquals(2, llmClient.callCount());

        application.getMailProcessor().processUnread();

        assertEquals("Reprocessing an already-seen message must not call the LLM/tool loop again",
                2, llmClient.callCount());
        assertEquals("Хорошо, напомню завтра в 10 позвонить Ивану.", mailChannel.getReplyBody("msg-1"));
    }

    @Test
    public void plannedItemsScenarioFindsPersistedReminder() {
        Path seenPath = tempFolder.getRoot().toPath().resolve("seen.json");
        Path reminderPath = tempFolder.getRoot().toPath().resolve("reminders.json");
        Path auditPath = tempFolder.getRoot().toPath().resolve("audit.log");

        new FileReminderStore(reminderPath).add(new Reminder("Позвонить Ивану", "2026-08-14T10:00:00Z"));

        MockMailChannel mailChannel = new MockMailChannel();
        Msg msg = new Msg("msg-2", "ivan@example.invalid", "Планы", "Что у меня запланировано?");
        mailChannel.addUnread(msg);

        Map<String, Object> findItemsArgs = new HashMap<>();
        findItemsArgs.put("query", "");
        ScriptedLlmClient llmClient = new ScriptedLlmClient(
                LlmResponse.toolCall(new ToolCall("call-1", "find_items", findItemsArgs)),
                LlmResponse.finalAnswer("У вас запланировано: позвонить Ивану."));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);

        Application application = ApplicationFactory.create(
                minimalConfig(3), mailChannel, llmClient, fixedClock, seenPath, reminderPath, auditPath);

        application.getMailProcessor().processUnread();

        assertEquals("У вас запланировано: позвонить Ивану.", mailChannel.getReplyBody("msg-2"));

        List<LlmMessage> secondCallMessages = llmClient.messagesForCall(1);
        boolean sawPersistedReminder = false;
        for (LlmMessage message : secondCallMessages) {
            if (message.isToolResult() && message.getContent() != null
                    && message.getContent().contains("Позвонить Ивану")) {
                sawPersistedReminder = true;
            }
        }
        assertTrue("find_items tool result must reflect the reminder persisted before this run",
                sawPersistedReminder);
    }

    @Test
    public void currentDateScenarioIsDeterministicViaFixedClock() {
        Path seenPath = tempFolder.getRoot().toPath().resolve("seen.json");
        Path reminderPath = tempFolder.getRoot().toPath().resolve("reminders.json");
        Path auditPath = tempFolder.getRoot().toPath().resolve("audit.log");

        MockMailChannel mailChannel = new MockMailChannel();
        Msg msg = new Msg("msg-3", "ivan@example.invalid", "Дата", "Какое сегодня число?");
        mailChannel.addUnread(msg);

        ScriptedLlmClient llmClient = new ScriptedLlmClient(
                LlmResponse.toolCall(new ToolCall("call-1", "current_datetime", new HashMap<>())),
                LlmResponse.finalAnswer("Сегодня 13 августа 2026 года."));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);

        Application application = ApplicationFactory.create(
                minimalConfig(3), mailChannel, llmClient, fixedClock, seenPath, reminderPath, auditPath);

        application.getMailProcessor().processUnread();

        assertEquals("Сегодня 13 августа 2026 года.", mailChannel.getReplyBody("msg-3"));

        List<LlmMessage> secondCallMessages = llmClient.messagesForCall(1);
        boolean sawFixedInstant = false;
        for (LlmMessage message : secondCallMessages) {
            if (message.isToolResult() && "2026-08-13T09:00:00Z".equals(message.getContent())) {
                sawFixedInstant = true;
            }
        }
        assertTrue("current_datetime tool result must reflect the injected fixed Clock, not the system clock",
                sawFixedInstant);
    }

    @Test
    public void garbageMessageGetsGracefulReplyWithoutToolCallsAndBecomesSeen() {
        Path seenPath = tempFolder.getRoot().toPath().resolve("seen.json");
        Path reminderPath = tempFolder.getRoot().toPath().resolve("reminders.json");
        Path auditPath = tempFolder.getRoot().toPath().resolve("audit.log");

        MockMailChannel mailChannel = new MockMailChannel();
        Msg msg = new Msg("msg-4", "unknown@example.invalid", "???", "asdkjaskdjaskjd");
        mailChannel.addUnread(msg);

        ScriptedLlmClient llmClient = new ScriptedLlmClient(
                LlmResponse.finalAnswer("Извините, я не понял ваш запрос."));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);

        Application application = ApplicationFactory.create(
                minimalConfig(3), mailChannel, llmClient, fixedClock, seenPath, reminderPath, auditPath);

        application.getMailProcessor().processUnread();

        assertEquals("Извините, я не понял ваш запрос.", mailChannel.getReplyBody("msg-4"));
        assertTrue("Message must become seen after a successful graceful reply",
                new FileSeenStore(seenPath).isSeen("msg-4"));
        assertEquals("Graceful garbage handling must not invoke the tool loop",
                1, llmClient.callCount());
        assertTrue("No reminder should have been created for a garbage/empty message",
                new FileReminderStore(reminderPath).find("").isEmpty());
    }

    private static AppConfig minimalConfig(int maxSteps) {
        AppConfig.LlmConfig llm = new AppConfig.LlmConfig(
                "https://example.invalid/v1/chat/completions", "test-model", "TEST_LLM_API_KEY", 5000);
        AppConfig.AgentConfig agent = new AppConfig.AgentConfig(maxSteps);
        AppConfig.StoreConfig store = new AppConfig.StoreConfig("./data/reminders.json");
        AppConfig.MailConfig mail = new AppConfig.MailConfig(30, "TestProfile", "Inbox");
        return new AppConfig(llm, agent, store, mail);
    }

    /**
     * Deterministic scripted LlmClient: replays a fixed sequence of LlmResponse
     * values and records every chat() call's messages for assertions.
     */
    private static class ScriptedLlmClient implements LlmClient {

        private final Queue<LlmResponse> responses;
        private final List<List<LlmMessage>> recordedMessages = new ArrayList<>();

        ScriptedLlmClient(LlmResponse... responses) {
            this.responses = new LinkedList<>(java.util.Arrays.asList(responses));
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
            recordedMessages.add(new ArrayList<>(messages));
            if (responses.isEmpty()) {
                throw new IllegalStateException("Scripted LLM client exhausted - unexpected extra call");
            }
            return responses.poll();
        }

        int callCount() {
            return recordedMessages.size();
        }

        List<LlmMessage> messagesForCall(int index) {
            return recordedMessages.get(index);
        }
    }
}
