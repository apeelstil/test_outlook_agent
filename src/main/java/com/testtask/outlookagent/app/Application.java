package com.testtask.outlookagent.app;

import com.testtask.outlookagent.audit.AuditJournal;
import com.testtask.outlookagent.llm.LlmClient;
import com.testtask.outlookagent.mail.MailProcessor;

public class Application {

    private final MailProcessor mailProcessor;
    private final AuditJournal auditJournal;
    private final LlmClient llmClient;

    Application(MailProcessor mailProcessor, AuditJournal auditJournal, LlmClient llmClient) {
        this.mailProcessor = mailProcessor;
        this.auditJournal = auditJournal;
        this.llmClient = llmClient;
    }

    public MailProcessor getMailProcessor() {
        return mailProcessor;
    }

    public AuditJournal getAuditJournal() {
        return auditJournal;
    }

    LlmClient getLlmClient() {
        return llmClient;
    }
}
