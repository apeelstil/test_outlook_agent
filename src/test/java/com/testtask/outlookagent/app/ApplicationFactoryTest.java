package com.testtask.outlookagent.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.testtask.outlookagent.audit.FileAuditJournal;
import com.testtask.outlookagent.audit.NoOpAuditJournal;
import com.testtask.outlookagent.config.AppConfig;
import com.testtask.outlookagent.config.EnvSecretResolver;
import com.testtask.outlookagent.config.MissingSecretException;
import com.testtask.outlookagent.llm.HttpLlmClient;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.mail.MockMailChannel;
import com.testtask.outlookagent.mail.Msg;
import com.testtask.outlookagent.store.FileSeenStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RED spec (Этап 35): minimal application composition root that wires config +
 * dependencies into a runnable Application without a DI framework or static
 * globals (see PLAN.md Roadmap #12). Also fixes the deterministic env-based LLM
 * secret resolution contract required by CLAUDE.md ("секреты только из env,
 * имя переменной из конфига, секрет не появляется в exception message").
 */
public class ApplicationFactoryTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void createWiresRealFileBackedStoresAndRealAuditJournal() {
        Path seenPath = tempFolder.getRoot().toPath().resolve("seen.json");
        Path reminderPath = tempFolder.getRoot().toPath().resolve("reminders.json");
        Path auditPath = tempFolder.getRoot().toPath().resolve("audit.log");

        MockMailChannel mailChannel = new MockMailChannel();
        mailChannel.addUnread(new Msg("wiring-1", "a@example.invalid", "s", "any body"));

        LlmClient llmClient = (messages, tools) -> LlmResponse.finalAnswer("ok");

        Application application = ApplicationFactory.create(
                minimalConfig(3), mailChannel, llmClient, Clock.systemUTC(), seenPath, reminderPath, auditPath);

        assertNotNull(application.getMailProcessor());
        assertNotNull(application.getAuditJournal());
        assertFalse("Production wiring must never use NoOpAuditJournal",
                application.getAuditJournal() instanceof NoOpAuditJournal);
        assertTrue("Production wiring must use the real FileAuditJournal",
                application.getAuditJournal() instanceof FileAuditJournal);

        application.getMailProcessor().processUnread();

        assertTrue("Seen state must be persisted through a real FileSeenStore",
                new FileSeenStore(seenPath).isSeen("wiring-1"));
        assertFalse("Audit journal must actually persist entries to disk (real, not NoOp)",
                application.getAuditJournal().readAll().isEmpty());
    }

    @Test
    public void mockDemoModeRunsOneProcessUnreadCycleWithoutOutlookOrNetworkLlm() {
        Path seenPath = tempFolder.getRoot().toPath().resolve("seen.json");
        Path reminderPath = tempFolder.getRoot().toPath().resolve("reminders.json");
        Path auditPath = tempFolder.getRoot().toPath().resolve("audit.log");

        MockMailChannel mailChannel = new MockMailChannel();
        mailChannel.addUnread(new Msg("demo-1", "a@example.invalid", "s", "hello"));

        LlmClient scriptedLlmClient = (messages, tools) -> LlmResponse.finalAnswer("demo reply");

        Application application = ApplicationFactory.create(
                minimalConfig(3), mailChannel, scriptedLlmClient, Clock.systemUTC(),
                seenPath, reminderPath, auditPath);

        application.getMailProcessor().processUnread();

        assertEquals("demo reply", mailChannel.getReplyBody("demo-1"));
    }

    @Test
    public void envSecretResolverReturnsExistingValue() {
        Map<String, String> env = new HashMap<>();
        env.put("TEST_LLM_API_KEY", "sk-super-secret-value");

        EnvSecretResolver resolver = new EnvSecretResolver(env);

        assertEquals("sk-super-secret-value", resolver.resolve("TEST_LLM_API_KEY"));
    }

    @Test
    public void envSecretResolverFailsStartupOnMissingValueWithoutLeakingUnrelatedSecrets() {
        Map<String, String> env = new HashMap<>();
        env.put("UNRELATED_SECRET", "do-not-leak-this-value");

        EnvSecretResolver resolver = new EnvSecretResolver(env);

        try {
            resolver.resolve("TEST_LLM_API_KEY");
            fail("Expected a controlled startup failure for a missing env variable");
        } catch (MissingSecretException e) {
            assertTrue("Exception message should name the missing env variable for diagnosability",
                    e.getMessage().contains("TEST_LLM_API_KEY"));
            assertFalse("Exception message must never leak another secret's value",
                    e.getMessage().contains("do-not-leak-this-value"));
        }
    }

    @Test
    public void envSecretResolverFailsStartupOnBlankValueWithoutLeakingIt() {
        Map<String, String> env = new HashMap<>();
        env.put("TEST_LLM_API_KEY", "   ");

        EnvSecretResolver resolver = new EnvSecretResolver(env);

        try {
            resolver.resolve("TEST_LLM_API_KEY");
            fail("Expected a controlled startup failure for a blank env variable value");
        } catch (MissingSecretException e) {
            assertTrue(e.getMessage().contains("TEST_LLM_API_KEY"));
            assertFalse("Exception message must not contain the blank secret value itself",
                    e.getMessage().contains("   "));
        }
    }

    @Test
    public void createProductionResolvesApiKeyFromConfiguredEnvVariableAndBuildsHttpLlmClient() {
        Path seenPath = tempFolder.getRoot().toPath().resolve("seen.json");
        Path reminderPath = tempFolder.getRoot().toPath().resolve("reminders.json");
        Path auditPath = tempFolder.getRoot().toPath().resolve("audit.log");

        MockMailChannel mailChannel = new MockMailChannel();

        Map<String, String> env = new HashMap<>();
        env.put("TEST_LLM_API_KEY", "sk-real-secret-value");

        Application application = ApplicationFactory.createProduction(
                minimalConfig(3), mailChannel, Clock.systemUTC(), seenPath, reminderPath, auditPath, env);

        assertTrue("Production wiring must build a real HttpLlmClient from config.llm.apiKeyEnv, "
                        + "not a NoOp/mock implementation",
                application.getLlmClient() instanceof HttpLlmClient);
    }

    @Test
    public void createProductionFailsFastWhenConfiguredApiKeyEnvVariableIsMissingAndDoesNotLeakOtherSecrets() {
        Path seenPath = tempFolder.getRoot().toPath().resolve("seen.json");
        Path reminderPath = tempFolder.getRoot().toPath().resolve("reminders.json");
        Path auditPath = tempFolder.getRoot().toPath().resolve("audit.log");

        MockMailChannel mailChannel = new MockMailChannel();

        Map<String, String> env = new HashMap<>();
        env.put("UNRELATED_SECRET", "do-not-leak-this-value");

        try {
            ApplicationFactory.createProduction(
                    minimalConfig(3), mailChannel, Clock.systemUTC(), seenPath, reminderPath, auditPath, env);
            fail("Expected a controlled startup failure when config.llm.apiKeyEnv is not set in the environment");
        } catch (MissingSecretException e) {
            assertTrue("Exception message should name the configured env variable (TEST_LLM_API_KEY)",
                    e.getMessage().contains("TEST_LLM_API_KEY"));
            assertFalse("Exception message must never leak an unrelated secret's value",
                    e.getMessage().contains("do-not-leak-this-value"));
        }
    }

    @Test
    public void createProductionFailsFastWhenConfiguredApiKeyEnvVariableIsBlank() {
        Path seenPath = tempFolder.getRoot().toPath().resolve("seen.json");
        Path reminderPath = tempFolder.getRoot().toPath().resolve("reminders.json");
        Path auditPath = tempFolder.getRoot().toPath().resolve("audit.log");

        MockMailChannel mailChannel = new MockMailChannel();

        Map<String, String> env = new HashMap<>();
        env.put("TEST_LLM_API_KEY", "   ");

        try {
            ApplicationFactory.createProduction(
                    minimalConfig(3), mailChannel, Clock.systemUTC(), seenPath, reminderPath, auditPath, env);
            fail("Expected a controlled startup failure when config.llm.apiKeyEnv resolves to a blank value");
        } catch (MissingSecretException e) {
            assertTrue(e.getMessage().contains("TEST_LLM_API_KEY"));
            assertFalse("Exception message must not contain the blank secret value itself",
                    e.getMessage().contains("   "));
        }
    }

    private static AppConfig minimalConfig(int maxSteps) {
        AppConfig.LlmConfig llm = new AppConfig.LlmConfig(
                "https://example.invalid/v1/chat/completions", "test-model", "TEST_LLM_API_KEY", 5000);
        AppConfig.AgentConfig agent = new AppConfig.AgentConfig(maxSteps);
        AppConfig.StoreConfig store = new AppConfig.StoreConfig("./data/reminders.json");
        AppConfig.MailConfig mail = new AppConfig.MailConfig(30, "TestProfile", "Inbox");
        return new AppConfig(llm, agent, store, mail);
    }
}
