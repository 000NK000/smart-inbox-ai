package com.smartinbox.processor.mail;

import java.time.LocalDateTime;
import java.util.List;

public final class MailWindow {
    public static final int HOURS = 120;
    public static final List<String> SOURCES = List.of("GMAIL", "QQMAIL", "OUTLOOK", "Google Mail", "QQ Mail", "Outlook", "Microsoft 365");
    public static LocalDateTime cutoff() { return LocalDateTime.now().minusHours(HOURS); }
    private MailWindow() { }
}
