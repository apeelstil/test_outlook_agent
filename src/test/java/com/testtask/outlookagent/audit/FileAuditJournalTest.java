package com.testtask.outlookagent.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RED spec (Stage 31): minimal provider-neutral append-only audit journal
 * contract with a SHA-256 hash chain. No production code exists yet; see
 * PLAN.md "Security / audit" and Тестовое-задание §3.6/§9. AuditJournal,
 * FileAuditJournal and AuditEntry/AuditEvent do not exist yet - this is a
 * compile-level RED describing the API they must provide.
 */
public class FileAuditJournalTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void appendedEventsArePersistedAndVisibleToNewInstanceOnRestart() {
        Path path = journalPath();
        AuditJournal firstInstance = new FileAuditJournal(path);
        firstInstance.append(new AuditEvent("agent_mail_seen", "hash-ref-1", null, 1000L));
        firstInstance.append(new AuditEvent("agent_tool_call", null, "current_datetime", 1001L));

        AuditJournal secondInstance = new FileAuditJournal(path);
        List<AuditEntry> entries = secondInstance.readAll();

        assertEquals(2, entries.size());
        assertEquals("agent_mail_seen", entries.get(0).getEvent().getEventKey());
        assertEquals("agent_tool_call", entries.get(1).getEvent().getEventKey());
        assertEquals("current_datetime", entries.get(1).getEvent().getToolName());
    }

    @Test
    public void appendingAfterRestartDoesNotOverwritePreviousEntries() {
        Path path = journalPath();
        new FileAuditJournal(path).append(new AuditEvent("agent_mail_seen", "hash-ref-1", null, 1000L));

        AuditJournal restarted = new FileAuditJournal(path);
        restarted.append(new AuditEvent("agent_mail_seen", "hash-ref-2", null, 2000L));

        List<AuditEntry> entries = new FileAuditJournal(path).readAll();

        assertEquals(2, entries.size());
        assertEquals("hash-ref-1", entries.get(0).getEvent().getHashedMessageRef());
        assertEquals("hash-ref-2", entries.get(1).getEvent().getHashedMessageRef());
    }

    @Test
    public void chainLinksEachEntryToThePreviousEntrysHash() {
        Path path = journalPath();
        AuditJournal journal = new FileAuditJournal(path);
        journal.append(new AuditEvent("agent_mail_seen", "hash-ref-1", null, 1000L));
        journal.append(new AuditEvent("agent_tool_call", null, "current_datetime", 1001L));
        journal.append(new AuditEvent("agent_mail_seen", "hash-ref-2", null, 1002L));

        List<AuditEntry> entries = new FileAuditJournal(path).readAll();

        assertEquals(3, entries.size());
        assertNotNull(entries.get(0).getHash());
        assertEquals(entries.get(0).getHash(), entries.get(1).getPreviousHash());
        assertEquals(entries.get(1).getHash(), entries.get(2).getPreviousHash());
    }

    @Test
    public void chainIntegrityHoldsAcrossRestartWhenUntampered() {
        Path path = journalPath();
        AuditJournal journal = new FileAuditJournal(path);
        journal.append(new AuditEvent("agent_mail_seen", "hash-ref-1", null, 1000L));
        journal.append(new AuditEvent("agent_tool_call", null, "current_datetime", 1001L));

        AuditJournal afterRestart = new FileAuditJournal(path);
        afterRestart.append(new AuditEvent("agent_mail_seen", "hash-ref-2", null, 1002L));

        AuditJournal afterSecondRestart = new FileAuditJournal(path);
        assertTrue(afterSecondRestart.verifyChainIntegrity());
    }

    @Test
    public void tamperedJournalFileFailsChainIntegrityCheckAfterRestart() throws Exception {
        Path path = journalPath();
        AuditJournal journal = new FileAuditJournal(path);
        journal.append(new AuditEvent("agent_mail_seen", "hash-ref-1", null, 1000L));
        journal.append(new AuditEvent("agent_tool_call", null, "current_datetime", 1001L));
        journal.append(new AuditEvent("agent_mail_seen", "hash-ref-2", null, 1002L));

        byte[] bytes = Files.readAllBytes(path);
        int tamperIndex = bytes.length / 3;
        bytes[tamperIndex] = (byte) ((bytes[tamperIndex] + 1) % 256);
        Files.write(path, bytes);

        AuditJournal restarted = new FileAuditJournal(path);

        assertFalse(restarted.verifyChainIntegrity());
    }

    private Path journalPath() {
        return new File(temporaryFolder.getRoot(), "audit.log").toPath();
    }
}
