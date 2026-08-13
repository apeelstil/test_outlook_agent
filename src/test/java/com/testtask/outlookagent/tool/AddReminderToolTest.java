package com.testtask.outlookagent.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.testtask.outlookagent.store.FileReminderStore;
import com.testtask.outlookagent.store.Reminder;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RED spec: describes the expected AddReminderTool contract before any
 * production code exists. See PLAN.md TDD roadmap.
 */
public class AddReminderToolTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void hasStableToolName() {
        FileReminderStore store = new FileReminderStore(newStorePath());
        AddReminderTool tool = new AddReminderTool(store);

        assertEquals("add_reminder", tool.getName());
    }

    @Test
    public void executePersistsReminderAndReturnsSuccessResult() {
        FileReminderStore store = new FileReminderStore(newStorePath());
        AddReminderTool tool = new AddReminderTool(store);

        Map<String, Object> args = new HashMap<>();
        args.put("text", "call sample contact");
        args.put("dueIso", "2026-08-14T07:00:00Z");

        Object result = tool.execute(args);

        assertEquals("Reminder added", result);

        List<Reminder> found = store.find("sample contact");
        assertEquals(1, found.size());
        assertEquals("call sample contact", found.get(0).getText());
        assertEquals("2026-08-14T07:00:00Z", found.get(0).getDueIso());
    }

    @Test
    public void executeRejectsMissingTextArgument() {
        FileReminderStore store = new FileReminderStore(newStorePath());
        AddReminderTool tool = new AddReminderTool(store);

        Map<String, Object> args = new HashMap<>();
        args.put("dueIso", "2026-08-14T07:00:00Z");

        try {
            tool.execute(args);
            fail("Expected IllegalArgumentException for missing text argument");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        assertTrue(store.find("sample contact").isEmpty());
    }

    private Path newStorePath() {
        return new File(temporaryFolder.getRoot(), "reminders.json").toPath();
    }
}
