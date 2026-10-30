package com.e9rental.connector;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** A validated, provider-independent direct Gmail search request. */
public record GmailSearchPlan(String senderEmail, List<String> topicKeywords,
        LocalDate startDateInclusive, LocalDate endDateInclusive, MailScope scope, QueryKind queryKind, int maxMessages) {
    private static final Pattern EMAIL = Pattern.compile(
        "(?i)[a-z0-9._%+-]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    private static final Pattern TOPIC = Pattern.compile("[a-z0-9]{1,40}");

    public enum MailScope { REGULAR_MAIL }
    public enum QueryKind { TOPIC, TYPED_SEARCH, DATE, RECENT, DIRECT_SEARCH }

    public GmailSearchPlan(String senderEmail, List<String> topicKeywords,
            LocalDate startDateInclusive, LocalDate endDateInclusive, MailScope scope) {
        this(senderEmail,topicKeywords,startDateInclusive,endDateInclusive,scope,QueryKind.TOPIC,20);
    }

    public GmailSearchPlan {
        if (senderEmail != null && !EMAIL.matcher(senderEmail).matches())
            throw new IllegalArgumentException("Invalid exact Gmail sender address");
        queryKind = Objects.requireNonNull(queryKind, "Gmail query kind is required");
        if (topicKeywords == null || topicKeywords.size() > 3 ||
                topicKeywords.stream().anyMatch(term -> term == null || !TOPIC.matcher(term).matches()))
            throw new IllegalArgumentException("Invalid Gmail topic keywords");
        if ((startDateInclusive == null) != (endDateInclusive == null) ||
                (startDateInclusive != null && startDateInclusive.isAfter(endDateInclusive)))
            throw new IllegalArgumentException("Invalid Gmail date interval");
        scope = Objects.requireNonNull(scope, "Gmail scope is required");
        if (maxMessages < 1 || maxMessages > 20 || (queryKind == QueryKind.RECENT && maxMessages != 10) ||
                (queryKind == QueryKind.DATE && (maxMessages != 20 || startDateInclusive == null || topicKeywords.size() != 0 || senderEmail != null)) ||
                (queryKind == QueryKind.RECENT && (startDateInclusive != null || !topicKeywords.isEmpty() || senderEmail != null)) ||
                ((queryKind == QueryKind.TOPIC || queryKind == QueryKind.TYPED_SEARCH) && topicKeywords.isEmpty()) ||
                (queryKind == QueryKind.DIRECT_SEARCH && senderEmail == null && topicKeywords.isEmpty() && startDateInclusive == null))
            throw new IllegalArgumentException("Invalid Gmail query bounds");
        topicKeywords = List.copyOf(topicKeywords);
        senderEmail = senderEmail == null ? null : senderEmail.toLowerCase(java.util.Locale.ROOT);
    }
}
