package com.testtask.outlookagent.mail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MockMailChannel implements MailChannel {

    private final List<Msg> unread = new ArrayList<>();
    private final Map<String, String> repliesByMsgId = new HashMap<>();

    public void addUnread(Msg msg) {
        unread.add(msg);
    }

    @Override
    public List<Msg> fetchUnread() {
        return unread;
    }

    @Override
    public void reply(Msg msg, String body) {
        repliesByMsgId.put(msg.getId(), body);
    }

    public String getReplyBody(String messageId) {
        return repliesByMsgId.get(messageId);
    }
}
