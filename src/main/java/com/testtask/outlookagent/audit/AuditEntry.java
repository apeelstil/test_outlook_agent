package com.testtask.outlookagent.audit;

public final class AuditEntry {

    private final AuditEvent event;
    private final String previousHash;
    private final String hash;

    public AuditEntry(AuditEvent event, String previousHash, String hash) {
        this.event = event;
        this.previousHash = previousHash;
        this.hash = hash;
    }

    public AuditEvent getEvent() {
        return event;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getHash() {
        return hash;
    }
}
