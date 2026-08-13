package com.testtask.outlookagent.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.testtask.outlookagent.config.AppConfig;
import com.testtask.outlookagent.config.MissingSecretException;
import com.testtask.outlookagent.llm.HttpLlmClient;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.llm.LlmResponse;
import com.testtask.outlookagent.mail.MockMailChannel;
import com.testtask.outlookagent.mail.Msg;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RED spec (Этап 38): production runtime composition contract — YAML config
 * → env secret (llm.apiKeyEnv) → production mail channel, built behind an
 * injectable MailChannelFactory so these tests never construct/load
 * JacobOutlookComFacade or touch Outlook → production Application →
 * PollingLoop using mail.pollSeconds. Also fixes that "mock-demo" is the only
 * executable arg that leads to MockDemoRunner; any other invocation must go
 * through this production wiring instead. See PLAN.md Application/PollLoop
 * boundary and JACOB strategy (the facade itself stays untested by mvn test,
 * only the seam above it is).
 */
public class ProductionLauncherTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void buildProductionApplicationPassesConfiguredProfileAndFolderToMailChannelFactory() {
        Map<String, String> env = new HashMap<>();
        env.put("TEST_LLM_API_KEY", "sk-real-secret-value");

        List<String> requestedProfile = new ArrayList<>();
        List<String> requestedFolder = new ArrayList<>();
        MockMailChannel fakeChannel = new MockMailChannel();

        ProductionLauncher.MailChannelFactory factory = (profile, folder) -> {
            requestedProfile.add(profile);
            requestedFolder.add(folder);
            return fakeChannel;
        };

        Application application = ProductionLauncher.buildProductionApplication(
                minimalConfig(), env, Clock.systemUTC(),
                tempFolder.getRoot().toPath().resolve("seen.json"),
                tempFolder.getRoot().toPath().resolve("reminders.json"),
                tempFolder.getRoot().toPath().resolve("audit.log"),
                factory);

        assertEquals(1, requestedProfile.size());
        assertEquals("TestProfile", requestedProfile.get(0));
        assertEquals("Inbox", requestedFolder.get(0));
        assertTrue("Production wiring must resolve the real HttpLlmClient from config.llm.apiKeyEnv",
                application.getLlmClient() instanceof HttpLlmClient);
    }

    @Test
    public void buildProductionApplicationFailsFastWhenApiKeyEnvMissingAndDoesNotLeakOtherSecrets() {
        Map<String, String> env = new HashMap<>();
        env.put("UNRELATED_SECRET", "do-not-leak-this-value");

        ProductionLauncher.MailChannelFactory factory = (profile, folder) -> new MockMailChannel();

        try {
            ProductionLauncher.buildProductionApplication(
                    minimalConfig(), env, Clock.systemUTC(),
                    tempFolder.getRoot().toPath().resolve("seen.json"),
                    tempFolder.getRoot().toPath().resolve("reminders.json"),
                    tempFolder.getRoot().toPath().resolve("audit.log"),
                    factory);
            fail("Expected a controlled startup failure when config.llm.apiKeyEnv is missing from env");
        } catch (MissingSecretException e) {
            assertTrue(e.getMessage().contains("TEST_LLM_API_KEY"));
            assertFalse(e.getMessage().contains("do-not-leak-this-value"));
        }
    }

    @Test
    public void buildPollingLoopUsesConfiguredPollSecondsAndProcessesUnreadEachCycle() {
        MockMailChannel mailChannel = new MockMailChannel();
        mailChannel.addUnread(new Msg("runtime-1", "a@example.invalid", "s", "body"));

        LlmClient scriptedLlmClient = (messages, tools) -> LlmResponse.finalAnswer("runtime reply");

        Application application = ApplicationFactory.create(
                minimalConfig(), mailChannel, scriptedLlmClient, Clock.systemUTC(),
                tempFolder.getRoot().toPath().resolve("seen.json"),
                tempFolder.getRoot().toPath().resolve("reminders.json"),
                tempFolder.getRoot().toPath().resolve("audit.log"));

        List<Long> recordedSleepsMillis = new ArrayList<>();
        PollingLoop loop = ProductionLauncher.buildPollingLoop(
                minimalConfig(), application, millis -> recordedSleepsMillis.add(millis));

        loop.runCycles(1);

        assertEquals("runtime reply", mailChannel.getReplyBody("runtime-1"));
        assertEquals(1, recordedSleepsMillis.size());
        assertEquals(30_000L, (long) recordedSleepsMillis.get(0));
    }

    @Test
    public void isMockDemoModeDetectsOnlyTheExactMockDemoArgument() {
        assertTrue(ProductionLauncher.isMockDemoMode(new String[] {"mock-demo"}));
        assertFalse(ProductionLauncher.isMockDemoMode(new String[] {}));
        assertFalse(ProductionLauncher.isMockDemoMode(new String[] {"production"}));
        assertFalse(ProductionLauncher.isMockDemoMode(new String[] {"mock-demo", "extra"}));
    }

    private static AppConfig minimalConfig() {
        AppConfig.LlmConfig llm = new AppConfig.LlmConfig(
                "https://example.invalid/v1/chat/completions", "test-model", "TEST_LLM_API_KEY", 5000);
        AppConfig.AgentConfig agent = new AppConfig.AgentConfig(3);
        AppConfig.StoreConfig store = new AppConfig.StoreConfig("./data/reminders.json");
        AppConfig.MailConfig mail = new AppConfig.MailConfig(30, "TestProfile", "Inbox");
        return new AppConfig(llm, agent, store, mail);
    }
}
