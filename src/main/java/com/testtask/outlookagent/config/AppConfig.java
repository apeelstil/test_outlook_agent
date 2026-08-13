package com.testtask.outlookagent.config;

public class AppConfig {

    private final LlmConfig llm;
    private final AgentConfig agent;
    private final StoreConfig store;
    private final MailConfig mail;

    public AppConfig(LlmConfig llm, AgentConfig agent, StoreConfig store, MailConfig mail) {
        this.llm = llm;
        this.agent = agent;
        this.store = store;
        this.mail = mail;
    }

    public LlmConfig getLlm() {
        return llm;
    }

    public AgentConfig getAgent() {
        return agent;
    }

    public StoreConfig getStore() {
        return store;
    }

    public MailConfig getMail() {
        return mail;
    }

    public static class LlmConfig {
        private final String endpoint;
        private final String model;
        private final String apiKeyEnv;
        private final int timeoutMs;

        public LlmConfig(String endpoint, String model, String apiKeyEnv, int timeoutMs) {
            this.endpoint = endpoint;
            this.model = model;
            this.apiKeyEnv = apiKeyEnv;
            this.timeoutMs = timeoutMs;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public String getModel() {
            return model;
        }

        public String getApiKeyEnv() {
            return apiKeyEnv;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }
    }

    public static class AgentConfig {
        private final int maxSteps;

        public AgentConfig(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public int getMaxSteps() {
            return maxSteps;
        }
    }

    public static class StoreConfig {
        private final String path;

        public StoreConfig(String path) {
            this.path = path;
        }

        public String getPath() {
            return path;
        }
    }

    public static class MailConfig {
        private final int pollSeconds;
        private final String profile;
        private final String folder;

        public MailConfig(int pollSeconds, String profile, String folder) {
            this.pollSeconds = pollSeconds;
            this.profile = profile;
            this.folder = folder;
        }

        public int getPollSeconds() {
            return pollSeconds;
        }

        public String getProfile() {
            return profile;
        }

        public String getFolder() {
            return folder;
        }
    }
}
