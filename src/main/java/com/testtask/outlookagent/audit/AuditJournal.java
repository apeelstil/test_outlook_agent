package com.testtask.outlookagent.audit;

import java.util.List;

public interface AuditJournal {

    void append(AuditEvent event);

    List<AuditEntry> readAll();

    boolean verifyChainIntegrity();
}
