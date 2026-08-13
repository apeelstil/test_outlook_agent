package com.testtask.outlookagent.mail;

import com.testtask.outlookagent.agent.Agent;
import com.testtask.outlookagent.audit.AuditEvent;
import com.testtask.outlookagent.audit.AuditJournal;
import com.testtask.outlookagent.audit.NoOpAuditJournal;
import com.testtask.outlookagent.store.SeenStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MailProcessor {

    private static final Logger logger = LoggerFactory.getLogger(MailProcessor.class);

    private final MailChannel mailChannel;
    private final Agent agent;
    private final SeenStore seenStore;
    private final AuditJournal auditJournal;

    public MailProcessor(MailChannel mailChannel, Agent agent, SeenStore seenStore) {
        this(mailChannel, agent, seenStore, new NoOpAuditJournal());
    }

    public MailProcessor(MailChannel mailChannel, Agent agent, SeenStore seenStore, AuditJournal auditJournal) {
        this.mailChannel = mailChannel;
        this.agent = agent;
        this.seenStore = seenStore;
        this.auditJournal = auditJournal;
    }

    public void processUnread() {
        List<Msg> messages;
        try {
            messages = mailChannel.fetchUnread();
        } catch (RuntimeException e) {
            logger.warn("event=mail_fetch_failed");
            return;
        }

        for (Msg msg : messages) {
            if (seenStore.isSeen(msg.getId())) {
                continue;
            }

            String replyBody = agent.run(msg.getBody());

            try {
                mailChannel.reply(msg, replyBody);
            } catch (RuntimeException e) {
                logger.warn("event=mail_reply_failed");
                continue;
            }

            seenStore.markSeen(msg.getId());

            String hashedRef = hashMessageId(msg.getId());
            logger.info("event=agent_mail_seen ref={}", hashedRef);
            auditJournal.append(new AuditEvent("agent_mail_seen", hashedRef, null, System.currentTimeMillis()));
        }
    }

    private static String hashMessageId(String messageId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(messageId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

