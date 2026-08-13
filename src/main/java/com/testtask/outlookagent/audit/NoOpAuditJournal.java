package com.testtask.outlookagent.audit;

import java.util.Collections;
import java.util.List;

public final class NoOpAuditJournal implements AuditJournal {

    @Override
    public void append(AuditEvent event) {
    }

    @Override
    public List<AuditEntry> readAll() {
        return Collections.emptyList();
    }

    @Override
    public boolean verifyChainIntegrity() {
        return true;
    }
}
