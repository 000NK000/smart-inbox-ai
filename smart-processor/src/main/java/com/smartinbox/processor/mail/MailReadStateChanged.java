package com.smartinbox.processor.mail;

/** Local Inbox state only; never changes the provider mailbox or confirmed tasks. */
public record MailReadStateChanged(Long mailId, boolean read) { }
