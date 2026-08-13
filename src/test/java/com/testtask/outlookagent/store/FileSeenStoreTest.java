package com.testtask.outlookagent.store;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RED spec (Roadmap #9): describes the expected persistent SeenStore/FileSeenStore
 * contract before any production code exists. See PLAN.md "Идемпотентность" and
 * Тестовое-задание §3.6/§9 for the required durable dedup-by-id shape.
 */
public class FileSeenStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void newStoreDoesNotConsiderUnknownIdSeen() {
        SeenStore store = new FileSeenStore(seenFilePath());

        assertFalse(store.isSeen("unknown-id"));
    }

    @Test
    public void markSeenMakesIdSeen() {
        SeenStore store = new FileSeenStore(seenFilePath());

        store.markSeen("id-1");

        assertTrue(store.isSeen("id-1"));
    }

    @Test
    public void persistsSeenIdAcrossStoreInstancesAfterRestart() {
        File storeFile = new File(temporaryFolder.getRoot(), "seen.json");

        SeenStore firstInstance = new FileSeenStore(storeFile.toPath());
        firstInstance.markSeen("id-1");

        SeenStore secondInstanceAfterRestart = new FileSeenStore(storeFile.toPath());

        assertTrue(secondInstanceAfterRestart.isSeen("id-1"));
    }

    @Test
    public void duplicateMarkSeenIsSafeAndDoesNotCreateLogicalDuplicates() {
        File storeFile = new File(temporaryFolder.getRoot(), "seen.json");
        SeenStore store = new FileSeenStore(storeFile.toPath());

        store.markSeen("id-1");
        store.markSeen("id-1");

        assertTrue(store.isSeen("id-1"));

        SeenStore reopened = new FileSeenStore(storeFile.toPath());
        assertTrue(reopened.isSeen("id-1"));
        assertFalse(reopened.isSeen("id-2"));
    }

    @Test
    public void missingFileIsTreatedAsEmptyStore() {
        File neverCreatedFile = new File(temporaryFolder.getRoot(), "does-not-exist.json");

        SeenStore store = new FileSeenStore(neverCreatedFile.toPath());

        assertFalse(store.isSeen("id-1"));
    }

    @Test
    public void markSeenCreatesMissingParentDirectoriesOnFirstRunAndPersistsAcrossRestart() {
        File nestedStoreFile = new File(temporaryFolder.getRoot(), "nested/does-not-exist-yet/seen.json");
        SeenStore store = new FileSeenStore(nestedStoreFile.toPath());

        store.markSeen("id-1");

        assertTrue(store.isSeen("id-1"));

        SeenStore reopenedAfterRestart = new FileSeenStore(nestedStoreFile.toPath());
        assertTrue(reopenedAfterRestart.isSeen("id-1"));
    }

    private java.nio.file.Path seenFilePath() {
        return new File(temporaryFolder.getRoot(), "seen.json").toPath();
    }
}
