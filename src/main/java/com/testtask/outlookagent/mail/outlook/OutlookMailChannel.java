package com.testtask.outlookagent.mail.outlook;

import com.testtask.outlookagent.mail.MailChannel;
import com.testtask.outlookagent.mail.Msg;
import java.util.ArrayList;
import java.util.List;

public class OutlookMailChannel implements MailChannel {

    private final OutlookComFacade facade;
    private final String profile;
    private final String folder;

    public OutlookMailChannel(OutlookComFacade facade, String profile, String folder) {
        this.facade = facade;
        this.profile = profile;
        this.folder = folder;
    }

    @Override
    public List<Msg> fetchUnread() {
        List<Msg> messages = new ArrayList<>();
        for (OutlookMailData data : facade.fetchUnread(profile, folder)) {
            messages.add(new Msg(data.getEntryId(), data.getSender(), data.getSubject(), data.getBody()));
        }
        return messages;
    }

    @Override
    public void reply(Msg msg, String body) {
        facade.reply(msg.getId(), body);
    }
}
