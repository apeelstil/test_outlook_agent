package com.testtask.outlookagent.app;

import com.testtask.outlookagent.agent.Agent;
import com.testtask.outlookagent.audit.FileAuditJournal;
import com.testtask.outlookagent.config.AppConfig;
import com.testtask.outlookagent.config.EnvSecretResolver;
import com.testtask.outlookagent.llm.HttpLlmClient;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.mail.MailChannel;
import com.testtask.outlookagent.mail.MailProcessor;
import com.testtask.outlookagent.store.FileReminderStore;
import com.testtask.outlookagent.store.FileSeenStore;
import com.testtask.outlookagent.tool.AddReminderTool;
import com.testtask.outlookagent.tool.CurrentDateTimeTool;
import com.testtask.outlookagent.tool.FindItemsTool;
import com.testtask.outlookagent.tool.ToolRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

public final class ApplicationFactory {

    private ApplicationFactory() {
    }

    public static Application create(AppConfig config, MailChannel mailChannel, LlmClient llmClient, Clock clock,
            Path seenPath, Path reminderPath, Path auditPath) {
        FileSeenStore seenStore = new FileSeenStore(seenPath);
        FileReminderStore reminderStore = new FileReminderStore(reminderPath);
        FileAuditJournal auditJournal = new FileAuditJournal(auditPath);

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new CurrentDateTimeTool(clock));
        toolRegistry.register(new AddReminderTool(reminderStore));
        toolRegistry.register(new FindItemsTool(reminderStore));

        Agent agent = new Agent(llmClient, toolRegistry, config.getAgent().getMaxSteps(), auditJournal);
        MailProcessor mailProcessor = new MailProcessor(mailChannel, agent, seenStore, auditJournal);

        return new Application(mailProcessor, auditJournal, llmClient);
    }

    public static Application createProduction(AppConfig config, MailChannel mailChannel, Clock clock,
            Path seenPath, Path reminderPath, Path auditPath, Map<String, String> env) {
        EnvSecretResolver secretResolver = new EnvSecretResolver(env);
        String apiKey = secretResolver.resolve(config.getLlm().getApiKeyEnv());

        LlmClient llmClient = new HttpLlmClient(
                config.getLlm().getEndpoint(), config.getLlm().getModel(), apiKey, config.getLlm().getTimeoutMs());

        return create(config, mailChannel, llmClient, clock, seenPath, reminderPath, auditPath);
    }
}
