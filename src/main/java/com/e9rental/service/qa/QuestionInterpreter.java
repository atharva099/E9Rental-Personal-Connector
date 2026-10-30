package com.e9rental.service.qa;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.e9rental.connector.GmailSearchPlan;
/** Intentionally small transparent grammar; document text never enters this interpreter. */
@Component
public class QuestionInterpreter {
    private static final ZoneId QUESTION_ZONE=ZoneId.of("Asia/Kolkata");
    private final Clock clock;
    public QuestionInterpreter(){this(Clock.systemUTC());}
    @Autowired public QuestionInterpreter(Clock clock){this.clock=Objects.requireNonNull(clock);}
    public record Plan(List<String> terms,Set<String> sources,String classification,Instant from,Instant to,
            boolean commitments,boolean amounts,boolean dateAnswer,boolean browse,boolean unsupported,
            GmailSearchPlan gmailSearch) {
        public Plan(List<String> terms,Set<String> sources,String classification,Instant from,Instant to,
                boolean commitments,boolean amounts,boolean dateAnswer,boolean browse,boolean unsupported) {
            this(terms,sources,classification,from,to,commitments,amounts,dateAnswer,browse,unsupported,null);
        }
    }
    private static final Set<String> STOP=Set.of(("what which who when where how much tell me about show find list summarize summary "+
        "records record available say says do does did is are was were be will my a an the and or to of for in on with from across all source sources "+
        "please regarding related information document documents message messages email emails gmail drive google whatsapp classified as "+
        "category between before after since until commitment commitments made there have has been any based their can you").split(" "));
    public Plan interpret(String question) {
        if(question==null || question.isBlank() || question.length()>1000 ||
            question.codePoints().anyMatch(c->Character.isISOControl(c) || c==0xfffd))throw new IllegalArgumentException();
        for(int i=0;i<question.length();i++) {
            char c=question.charAt(i);
            if(Character.isHighSurrogate(c)){if(i+1>=question.length() || !Character.isLowSurrogate(question.charAt(++i)))throw new IllegalArgumentException();}
            else if(Character.isLowSurrogate(c))throw new IllegalArgumentException();
        }
        String q=question.toLowerCase(Locale.ROOT);Set<String> sources=new LinkedHashSet<>();
        if(Pattern.compile("\\b(gmail|emails?)\\b").matcher(q).find())sources.add("GMAIL");
        if(Pattern.compile("\\b(drive|google docs)\\b").matcher(q).find())sources.add("DRIVE");
        if(Pattern.compile("\\bwhatsapp\\b").matcher(q).find())sources.add("WHATSAPP");
        var category=Pattern.compile("\\b(?:classified as|category)\\s+(sales|marketing|verification|office|accounts|other|unclassified)\\b").matcher(q);
        String classification=category.find()?category.group(1):null;
        String text=q;
        // A single explicit fact-question form is supported for live Gmail. Restricting
        // the complete utterance keeps Gmail query construction deterministic.
        var gmailFact=Pattern.compile("^\\s*what\\s+does\\s+my\\s+gmail\\s+say\\s+about\\s+([a-z0-9]+(?:\\s+[a-z0-9]+){0,2})\\s*\\??\\s*$").matcher(q);
        boolean gmailFactQuestion=gmailFact.matches();
        if(gmailFactQuestion)text=gmailFact.group(1);
        var dateQuestion=Pattern.compile("^\\s*show\\s+my\\s+gmail\\s+emails?\\s+from\\s+(today|yesterday)\\s*[?.]?\\s*$").matcher(q);
        boolean gmailDateQuestion=dateQuestion.matches();LocalDate requestedDate=null;
        if(gmailDateQuestion){requestedDate=LocalDate.now(clock.withZone(QUESTION_ZONE));if("yesterday".equals(dateQuestion.group(1)))requestedDate=requestedDate.minusDays(1);text="";}
        var recentQuestion=Pattern.compile("^\\s*show\\s+my\\s+latest\\s+10\\s+gmail\\s+emails?\\s*[?.]?\\s*$").matcher(q);
        boolean gmailRecentQuestion=recentQuestion.matches();if(gmailRecentQuestion)text="";
        if(classification!=null)text=text.replaceAll("\\b(?:classified as|category)\\s+"+classification+"\\b"," ");
        else {
            var suffix=Pattern.compile("\\b(sales|marketing|verification|office|accounts|other|unclassified) category\\b").matcher(q);
            if(suffix.find()){classification=suffix.group(1);text=text.replace(suffix.group()," ");}
        }
        Instant from=null,to=null;
        GmailSearchPlan gmailSearch=null;
        if(gmailDateQuestion) {
            from=requestedDate.atStartOfDay(QUESTION_ZONE).toInstant();
            to=requestedDate.plusDays(1).atStartOfDay(QUESTION_ZONE).toInstant().minusNanos(1);
            gmailSearch=new GmailSearchPlan(null,List.of(),requestedDate,requestedDate,GmailSearchPlan.MailScope.REGULAR_MAIL,GmailSearchPlan.QueryKind.DATE,20);
            sources.clear();sources.add("GMAIL");
        } else if(gmailRecentQuestion) {
            gmailSearch=new GmailSearchPlan(null,List.of(),null,null,GmailSearchPlan.MailScope.REGULAR_MAIL,GmailSearchPlan.QueryKind.RECENT,10);
            sources.clear();sources.add("GMAIL");
        }
        var range=Pattern.compile("\\b(?:between|from) (\\d{4}-\\d{2}-\\d{2}) (?:and|to) (\\d{4}-\\d{2}-\\d{2})\\b").matcher(q);
        if(range.find()){
            from=day(range.group(1));to=end(range.group(2));text=text.replace(range.group()," ");
        } else {
            var dates=Pattern.compile("\\b(on|after|before|since|until) (\\d{4}-\\d{2}-\\d{2})\\b").matcher(q);
            while(dates.find()) {
                Instant date=day(dates.group(2));
                switch(dates.group(1)) {
                    case "on" -> {from=date;to=end(dates.group(2));}
                    case "after" -> from=date.plus(Duration.ofDays(1));
                    case "since" -> from=date;
                    case "before" -> to=date.minusNanos(1);
                    case "until" -> to=end(dates.group(2));
                }
                text=text.replace(dates.group()," ");
            }
        }
        var typedGmail=Pattern.compile("^\\s*find\\s+gmail\\s+emails?\\s+from\\s+([^\\s]+)\\s+about\\s+([a-z0-9]+(?:\\s+[a-z0-9]+){0,2})\\s+between\\s+(\\d{4}-\\d{2}-\\d{2})\\s+and\\s+(\\d{4}-\\d{2}-\\d{2})\\s*\\.?\\s*$").matcher(q);
        boolean typedGmailQuestion=typedGmail.matches();
        boolean typedGmailAttempt=Pattern.compile("^\\s*find\\s+gmail\\s+emails?\\s+from\\b").matcher(q).find();
        if (typedGmailQuestion) {
            try {
                var start=LocalDate.parse(typedGmail.group(3));
                var finish=LocalDate.parse(typedGmail.group(4));
                var keywords=List.of(typedGmail.group(2).split("\\s+"));
                gmailSearch=new GmailSearchPlan(typedGmail.group(1),keywords,start,finish,GmailSearchPlan.MailScope.REGULAR_MAIL,GmailSearchPlan.QueryKind.TYPED_SEARCH,20);
                var zone=ZoneId.of("Asia/Kolkata");
                from=start.atStartOfDay(zone).toInstant();
                to=finish.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1);
                sources.clear();sources.add("GMAIL");
                text=String.join(" ",keywords);
            } catch (DateTimeException | IllegalArgumentException invalidTypedSearch) {
                throw new IllegalArgumentException("Invalid typed Gmail search");
            }
        }
        if(Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(text).find() ||
            (from!=null && to!=null && from.isAfter(to)))throw new IllegalArgumentException();
        boolean unsupported=(typedGmailAttempt && !typedGmailQuestion) || Pattern.compile("\\b(total|sum|average|compare|difference|latest|earliest|yesterday|today|tomorrow|last week|last month)\\b").matcher(text).find();
        boolean commitments=Pattern.compile("\\bcommitments?\\b").matcher(text).find();
        boolean amounts=Pattern.compile("\\b(amount|cost|price)\\b|how much").matcher(text).find();
        boolean dateAnswer=Pattern.compile("\\bwhen\\b|what date").matcher(text).find();
        LinkedHashSet<String> terms=new LinkedHashSet<>();
        var simpleGmailTopic=Pattern.compile("^\\s*find\\s+gmail\\s+emails?\\s+about\\s+([a-z0-9]+(?:\\s+[a-z0-9]+){0,2})\\s*[?.]?\\s*$").matcher(q);
        boolean simpleGmailTopicFind=simpleGmailTopic.matches();
        boolean preserveGoogleTopic=simpleGmailTopicFind && sources.equals(Set.of("GMAIL"));
        var words=Pattern.compile("[\\p{L}\\p{N}]+").matcher(text);
        while(words.find()) {
            String word=words.group();
            if((STOP.contains(word) && !(preserveGoogleTopic && word.equals("google"))) ||
                    Set.of("amount","cost","price","date").contains(word))continue;
            if(word.endsWith("s") && word.length()>4)word=word.substring(0,word.length()-1);
            if(word.equals("quote"))word="quotation";
            terms.add(word);
        }
        if(gmailSearch!=null) {
            terms.clear();terms.addAll(gmailSearch.topicKeywords());
        }
        if(terms.size()>8)unsupported=true;
        boolean browse=typedGmailQuestion || simpleGmailTopicFind || gmailFactQuestion || gmailDateQuestion || gmailRecentQuestion || Pattern.compile("\\b(show|list|summarize)\\b").matcher(text).find() &&
            Pattern.compile("\\b(records|messages|emails|documents|sources)\\b").matcher(text).find();
        return new Plan(List.copyOf(terms),Collections.unmodifiableSet(sources),classification,from,to,commitments,amounts,dateAnswer,browse,unsupported,gmailSearch);
    }
    private Instant day(String value){try{return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();}catch(DateTimeException e){throw new IllegalArgumentException();}}
    private Instant end(String value){return day(value).plus(Duration.ofDays(1)).minusNanos(1);}
}
