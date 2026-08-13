package com.testtask.outlookagent.app;

import com.testtask.outlookagent.config.AppConfig;
import com.testtask.outlookagent.config.ConfigLoader;
import com.testtask.outlookagent.mail.outlook.JacobOutlookComFacade;
import com.testtask.outlookagent.mail.outlook.OutlookMailChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executable entry point. {@code mock-demo} runs the Outlook/network-free demo
 * (see MockDemoRunner); any other invocation loads YAML config and runs the
 * production flow: JacobOutlookComFacade -> OutlookMailChannel -> production
 * Application -> PollingLoop.runForever(). Live Outlook behaviour stays "requires
 * target environment verification" until run on the target Windows/Outlook
 * environment (see PLAN.md JACOB strategy).
 */
public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String DEFAULT_CONFIG_PATH = "config.yaml";

    private Main() {
    }

    public static void main(String[] args) {
        if (ProductionLauncher.isMockDemoMode(args)) {
            runMockDemo();
            return;
        }

        String configPath = args.length > 0 ? args[0] : DEFAULT_CONFIG_PATH;
        AppConfig config = ConfigLoader.load(configPath);

        Path reminderPath = Paths.get(config.getStore().getPath());
        Path seenPath = reminderPath.resolveSibling("seen.json");
        Path auditPath = reminderPath.resolveSibling("audit.log");

        Application application = ProductionLauncher.buildProductionApplication(
                config, System.getenv(), Clock.systemUTC(),
                seenPath, reminderPath, auditPath,
                (profile, folder) -> new OutlookMailChannel(new JacobOutlookComFacade(), profile, folder));

        logger.info("event=production_startup_complete");

        PollingLoop pollingLoop = ProductionLauncher.buildPollingLoop(config, application, Main::sleepQuietly);
        pollingLoop.runForever();
    }

    private static void runMockDemo() {
        String replyBody = new MockDemoRunner().run();
        System.out.println("Mock demo: processed 1 unread message, agent replied:");
        System.out.println(replyBody);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
