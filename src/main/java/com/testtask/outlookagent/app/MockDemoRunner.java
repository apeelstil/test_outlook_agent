package com.testtask.outlookagent.app;

import com.testtask.outlookagent.config.AppConfig;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.mail.MockMailChannel;
import com.testtask.outlookagent.mail.Msg;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Manual local demo of the core mail-processing flow: scripted mock mail in,
 * real Agent/tools/stores, deterministic scripted LlmClient, reply out. No
 * Outlook/JACOB, no network, no secrets. See PLAN.md delivery/demo section.
 */
public final class MockDemoRunner {

    private static final String DEMO_MESSAGE_ID = "demo-1";

    public String run() {
        Path demoDir = createDemoDir();
        Path seenPath = demoDir.resolve("seen.json");
        Path reminderPath = demoDir.resolve("reminders.json");
        Path auditPath = demoDir.resolve("audit.log");

        MockMailChannel mailChannel = new MockMailChannel();
        mailChannel.addUnread(new Msg(DEMO_MESSAGE_ID, "demo.user@example.invalid",
                "Напоминание", "Напомни завтра в 10 позвонить Ивану"));

        LlmClient scriptedLlmClient = (messages, tools) -> LlmResponse.finalAnswer(
                "Демо: получил ваше письмо и ответил без Outlook и сети.");

        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);

        Application application = ApplicationFactory.create(
                demoConfig(), mailChannel, scriptedLlmClient, fixedClock, seenPath, reminderPath, auditPath);

        application.getMailProcessor().processUnread();

        return mailChannel.getReplyBody(DEMO_MESSAGE_ID);
    }

    private static Path createDemoDir() {
        try {
            return Files.createTempDirectory("outlook-agent-mock-demo");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create local demo data directory", e);
        }
    }

    private static AppConfig demoConfig() {
        AppConfig.LlmConfig llm = new AppConfig.LlmConfig(
                "https://example.invalid/v1/chat/completions", "demo-model", "DEMO_LLM_API_KEY", 5000);
        AppConfig.AgentConfig agent = new AppConfig.AgentConfig(3);
        AppConfig.StoreConfig store = new AppConfig.StoreConfig("./data/reminders.json");
        AppConfig.MailConfig mail = new AppConfig.MailConfig(30, "DemoProfile", "Inbox");
        return new AppConfig(llm, agent, store, mail);
    }

    public static void main(String[] args) {
        if (args.length != 1 || !"mock-demo".equals(args[0])) {
            System.err.println("Usage: java -jar outlook-agent.jar mock-demo");
            System.exit(1);
            return;
        }

        String replyBody = new MockDemoRunner().run();

        System.out.println("Mock demo: processed 1 unread message, agent replied:");
        System.out.println(replyBody);
    }
}
