package com.testtask.outlookagent.mail;

import com.testtask.outlookagent.agent.Agent;
import com.testtask.outlookagent.store.SeenStore;

public class MailProcessor {

    private final MailChannel mailChannel;
    private final Agent agent;
    private final SeenStore seenStore;

    public MailProcessor(MailChannel mailChannel, Agent agent, SeenStore seenStore) {
        this.mailChannel = mailChannel;
        this.agent = agent;
        this.seenStore = seenStore;
    }

    public void processUnread() {
        for (Msg msg : mailChannel.fetchUnread()) {
            if (seenStore.isSeen(msg.getId())) {
                continue;
            }

            String replyBody = agent.run(msg.getBody());

            try {
                mailChannel.reply(msg, replyBody);
            } catch (RuntimeException e) {
                continue;
            }

            seenStore.markSeen(msg.getId());
        }
    }
}
