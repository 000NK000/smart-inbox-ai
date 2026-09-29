package com.smartinbox.collector.service;

/** Operational metadata only: never contains addresses, subjects or message bodies. */
public record MailSyncReport(boolean success, String state, String mode, int scanned,
        int published, int bodyFetched, int skipped, String failureCode, boolean reconciled) {
    public static MailSyncReport failed(String mode, String code) {
        return new MailSyncReport(false, "failed", mode, 0, 0, 0, 0, code, false);
    }
}
