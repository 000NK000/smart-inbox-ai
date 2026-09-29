package com.smartinbox.collector.service;

public interface EmailService {
    /**
     * Fetch all recent received mail, regardless of the source mailbox's read state.
     */
    void fetchRecentEmails();

    /** Retrieve exactly one configured IMAP channel. */
    MailSyncReport fetchSource(String source);
}
