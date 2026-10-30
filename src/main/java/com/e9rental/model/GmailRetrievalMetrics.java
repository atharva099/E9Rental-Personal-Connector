package com.e9rental.model;

/** Aggregate direct-query counts only; contains no Gmail identifiers or content. */
public record GmailRetrievalMetrics(int gmailRestAttempts,int profileRequests,int listRequests,
        int messageRequests,int attachmentRequests,int oauthRefreshAttempts,long responseBytes,
        int messagesRetrieved) {}
