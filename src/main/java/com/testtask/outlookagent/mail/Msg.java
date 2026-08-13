package com.testtask.outlookagent.mail;

public class Msg {

    private final String id;
    private final String sender;
    private final String subject;
    private final String body;

    public Msg(String id, String sender, String subject, String body) {
        this.id = id;
        this.sender = sender;
        this.subject = subject;
        this.body = body;
    }

    public String getId() {
        return id;
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
