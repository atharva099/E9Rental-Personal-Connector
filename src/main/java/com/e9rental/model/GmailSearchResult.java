package com.e9rental.model;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Minimal transient row for one result from a bounded direct Gmail search. */
public record GmailSearchResult(String subject,String sender,@JsonProperty("received_at") Instant receivedAt,
        String excerpt,@JsonProperty("selection_id") String selectionId) {}
