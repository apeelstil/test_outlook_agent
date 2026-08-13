package com.testtask.outlookagent.mail.outlook;

public class OutlookMailData {

    private final String entryId;
    private final String sender;
    private final String subject;
    private final String body;

    public OutlookMailData(String entryId, String sender, String subject, String body) {
        this.entryId = entryId;
        this.sender = sender;
        this.subject = subject;
        this.body = body;
    }

    public String getEntryId() {
        return entryId;
    }

    public String getSender() {
        return sender;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }
}
