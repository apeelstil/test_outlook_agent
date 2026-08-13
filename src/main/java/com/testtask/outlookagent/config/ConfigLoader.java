package com.testtask.outlookagent.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public class ConfigLoader {

    public static AppConfig load(String path) {
        try (InputStream in = new FileInputStream(path)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);

            Map<String, Object> llmSection = (Map<String, Object>) root.get("llm");
            Map<String, Object> agentSection = (Map<String, Object>) root.get("agent");
            Map<String, Object> storeSection = (Map<String, Object>) root.get("store");
            Map<String, Object> mailSection = (Map<String, Object>) root.get("mail");

            AppConfig.LlmConfig llm = new AppConfig.LlmConfig(
                    (String) llmSection.get("endpoint"),
                    (String) llmSection.get("model"),
                    (String) llmSection.get("apiKeyEnv"),
                    (Integer) llmSection.get("timeoutMs"));

            AppConfig.AgentConfig agent = new AppConfig.AgentConfig(
                    (Integer) agentSection.get("maxSteps"));

            AppConfig.StoreConfig store = new AppConfig.StoreConfig(
                    (String) storeSection.get("path"));

            AppConfig.MailConfig mail = new AppConfig.MailConfig(
                    (Integer) mailSection.get("pollSeconds"),
                    (String) mailSection.get("profile"),
                    (String) mailSection.get("folder"));

            return new AppConfig(llm, agent, store, mail);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config from " + path, e);
        }
    }
}
