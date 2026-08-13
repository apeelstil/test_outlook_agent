package com.testtask.outlookagent.app;

import com.testtask.outlookagent.config.AppConfig;
import com.testtask.outlookagent.mail.MailChannel;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Production runtime composition (see PLAN.md Application/PollLoop boundary): YAML config
 * + env secret (llm.apiKeyEnv) -> production mail channel (behind an injectable
 * MailChannelFactory so this class never constructs JacobOutlookComFacade itself) ->
 * production Application -> PollingLoop using mail.pollSeconds.
 */
public final class ProductionLauncher {

    private static final String MOCK_DEMO_ARG = "mock-demo";

    private ProductionLauncher() {
    }

    public interface MailChannelFactory {
        MailChannel create(String profile, String folder);
    }

    public static Application buildProductionApplication(AppConfig config, Map<String, String> env, Clock clock,
            Path seenPath, Path reminderPath, Path auditPath, MailChannelFactory mailChannelFactory) {
        MailChannel mailChannel = mailChannelFactory.create(config.getMail().getProfile(), config.getMail().getFolder());
        return ApplicationFactory.createProduction(config, mailChannel, clock, seenPath, reminderPath, auditPath, env);
    }

    public static PollingLoop buildPollingLoop(AppConfig config, Application application, PollingLoop.Sleeper sleeper) {
        Runnable cycle = () -> application.getMailProcessor().processUnread();
        return new PollingLoop(cycle, config.getMail().getPollSeconds(), sleeper);
    }

    public static boolean isMockDemoMode(String[] args) {
        return args.length == 1 && MOCK_DEMO_ARG.equals(args[0]);
    }
}
