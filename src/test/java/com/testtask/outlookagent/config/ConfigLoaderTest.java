package com.testtask.outlookagent.config;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.net.URISyntaxException;
import org.junit.Test;

/**
 * RED spec (Roadmap #2 / Этап 3): describes the expected ConfigLoader/AppConfig
 * contract before any production code exists. See PLAN.md TDD roadmap and
 * Тестовое-задание §3.5 for the required fields.
 */
public class ConfigLoaderTest {

    @Test
    public void loadsAllRequiredFieldsFromYaml() throws URISyntaxException {
        File yamlFile = new File(
                getClass().getClassLoader().getResource("config-test.yaml").toURI());

        AppConfig config = ConfigLoader.load(yamlFile.getAbsolutePath());

        assertEquals("https://example.invalid/v1/chat/completions", config.getLlm().getEndpoint());
        assertEquals("test-model", config.getLlm().getModel());
        assertEquals("TEST_LLM_API_KEY", config.getLlm().getApiKeyEnv());
        assertEquals(5000, config.getLlm().getTimeoutMs());

        assertEquals(5, config.getAgent().getMaxSteps());

        assertEquals("./data/reminders.json", config.getStore().getPath());

        assertEquals(30, config.getMail().getPollSeconds());
        assertEquals("TestProfile", config.getMail().getProfile());
        assertEquals("Inbox", config.getMail().getFolder());
    }
}
