package com.e9rental.model;

import java.time.Instant;
import java.util.List;

/** Transient, deliberately identifier-free representation of one selected Gmail message. */
public record GmailMessageDetail(String subject,String sender,String recipient,Instant receivedAt,String bodyText,
        boolean bodyAvailable,boolean incomplete,boolean bodyTruncated,List<String> attachmentNames,
        boolean attachmentNamesTruncated) {
    public GmailMessageDetail { attachmentNames=List.copyOf(attachmentNames); }
}
