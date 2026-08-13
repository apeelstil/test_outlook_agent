package com.testtask.outlookagent.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.testtask.outlookagent.store.FileReminderStore;
import com.testtask.outlookagent.store.Reminder;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RED spec: describes the expected FindItemsTool contract before any
 * production code exists. See PLAN.md TDD roadmap.
 */
public class FindItemsToolTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void hasStableToolName() {
        FileReminderStore store = new FileReminderStore(newStorePath());
        FindItemsTool tool = new FindItemsTool(store);

        assertEquals("find_items", tool.getName());
    }

    @Test
    public void executeReturnsMatchingRemindersOnly() {
        FileReminderStore store = new FileReminderStore(newStorePath());
        store.add(new Reminder("call sample contact", "2026-08-14T07:00:00Z"));
        store.add(new Reminder("buy groceries", "2026-08-15T09:00:00Z"));

        FindItemsTool tool = new FindItemsTool(store);

        Map<String, Object> args = new HashMap<>();
        args.put("query", "sample contact");

        Object result = tool.execute(args);

        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<Reminder> reminders = (List<Reminder>) result;

        assertEquals(1, reminders.size());
        assertEquals("call sample contact", reminders.get(0).getText());
        assertEquals("2026-08-14T07:00:00Z", reminders.get(0).getDueIso());
    }

    @Test
    public void executeReturnsEmptyListWhenNoMatches() {
        FileReminderStore store = new FileReminderStore(newStorePath());
        store.add(new Reminder("buy groceries", "2026-08-15T09:00:00Z"));

        FindItemsTool tool = new FindItemsTool(store);

        Map<String, Object> args = new HashMap<>();
        args.put("query", "no such reminder");

        Object result = tool.execute(args);

        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<Reminder> reminders = (List<Reminder>) result;

        assertTrue(reminders.isEmpty());
    }

    @Test
    public void executeRejectsMissingQueryArgument() {
        FileReminderStore store = new FileReminderStore(newStorePath());
        FindItemsTool tool = new FindItemsTool(store);

        Map<String, Object> args = new HashMap<>();

        try {
            tool.execute(args);
            fail("Expected IllegalArgumentException for missing query argument");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private java.nio.file.Path newStorePath() {
        return new File(temporaryFolder.getRoot(), "reminders.json").toPath();
    }
}
