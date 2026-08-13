package com.testtask.outlookagent.mail;

import java.util.List;

public interface MailChannel {

    List<Msg> fetchUnread();

    void reply(Msg msg, String body);
}
