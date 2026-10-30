package com.e9rental.model;

import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailSearchPlan;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Strict, provider-independent filters accepted by the explicit live Gmail search API. */
public record GmailSearchFilters(String keyword,String sender,String startDate,String endDate) {
    private static final Pattern TOPIC_WORD=Pattern.compile("[A-Za-z0-9]{1,40}");
    private static final Pattern ISO_DATE=Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final LocalDate MAX_SUPPORTED_DATE=LocalDate.of(9999,12,31);
    public static final int MAX_DATE_RANGE_DAYS=366;

    public GmailSearchPlan toPlan() {
        String normalizedKeyword=keyword==null?"":keyword.strip();
        List<String> words=normalizedKeyword.isEmpty()?List.of():Arrays.asList(normalizedKeyword.split("\\s+"));
        if(words.size()>3 || words.stream().anyMatch(word->!TOPIC_WORD.matcher(word).matches()))throw invalid();
        words=words.stream().map(word->word.toLowerCase(Locale.ROOT)).toList();

        String normalizedSender=sender==null?null:sender.strip();
        if(normalizedSender!=null && normalizedSender.isEmpty())normalizedSender=null;
        LocalDate from=parseDate(startDate),to=parseDate(endDate);
        if((from==null)!=(to==null) || (from==null && words.isEmpty() && normalizedSender==null))throw invalid();
        if(from!=null && (from.isAfter(to) || to.equals(MAX_SUPPORTED_DATE) ||
                ChronoUnit.DAYS.between(from,to)>=MAX_DATE_RANGE_DAYS))throw invalid();
        try {
            return new GmailSearchPlan(normalizedSender,words,from,to,GmailSearchPlan.MailScope.REGULAR_MAIL,
                GmailSearchPlan.QueryKind.DIRECT_SEARCH,20);
        } catch(IllegalArgumentException rejected) { throw invalid(); }
    }

    private static LocalDate parseDate(String value) {
        if(value==null || value.isBlank())return null;
        String text=value.strip();
        if(!ISO_DATE.matcher(text).matches())throw invalid();
        try { return LocalDate.parse(text); }
        catch(RuntimeException badDate) { throw invalid(); }
    }

    private static GmailFailure invalid() { return new GmailFailure(400,"Invalid Gmail search filters"); }
}
