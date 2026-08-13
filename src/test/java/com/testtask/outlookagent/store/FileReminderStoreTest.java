package com.testtask.outlookagent.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RED spec: describes the expected persistent Reminder/FileReminderStore
 * contract before any production code exists. See PLAN.md TDD roadmap.
 */
public class FileReminderStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void persistsReminderAcrossStoreInstances() {
        File storeFile = new File(temporaryFolder.getRoot(), "reminders.json");

        FileReminderStore firstStore = new FileReminderStore(storeFile.toPath());
        Reminder reminder = new Reminder("call sample contact", "2026-08-14T07:00:00Z");
        firstStore.add(reminder);

        List<Reminder> foundInFirstStore = firstStore.find("sample contact");
        assertEquals(1, foundInFirstStore.size());
        assertEquals("call sample contact", foundInFirstStore.get(0).getText());
        assertEquals("2026-08-14T07:00:00Z", foundInFirstStore.get(0).getDueIso());

        FileReminderStore secondStore = new FileReminderStore(storeFile.toPath());
        List<Reminder> foundInSecondStore = secondStore.find("sample contact");

        assertTrue(!foundInSecondStore.isEmpty());
        assertEquals("call sample contact", foundInSecondStore.get(0).getText());
        assertEquals("2026-08-14T07:00:00Z", foundInSecondStore.get(0).getDueIso());
    }
}
