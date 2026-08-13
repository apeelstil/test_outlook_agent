package com.testtask.outlookagent.audit;

public final class AuditEvent {

    private final String eventKey;
    private final String hashedMessageRef;
    private final String toolName;
    private final long timestamp;

    public AuditEvent(String eventKey, String hashedMessageRef, String toolName, long timestamp) {
        this.eventKey = eventKey;
        this.hashedMessageRef = hashedMessageRef;
        this.toolName = toolName;
        this.timestamp = timestamp;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getHashedMessageRef() {
        return hashedMessageRef;
    }

    public String getToolName() {
        return toolName;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
