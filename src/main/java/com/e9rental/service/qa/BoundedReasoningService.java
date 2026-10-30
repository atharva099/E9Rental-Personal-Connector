package com.e9rental.service.qa;

import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.UnifiedRepository;
import com.e9rental.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded, auditable rules. No external provider or document-supplied instructions. */
@Service
public class BoundedReasoningService {
    private final UnifiedRepository repository;private final Clock clock;private final String reference,gmailReference,siteGroup;private final WhatsAppPolicy policy;private final boolean salesSnapshotComplete;
    public BoundedReasoningService(UnifiedRepository repository,Clock clock,WhatsAppPolicy policy,
        @Value("${QA_SALES_SNAPSHOT_COMPLETE:false}") boolean salesSnapshotComplete,
        @Value("${QA_REFERENCE_INSTANT:}") String reference,@Value("${GMAIL_REFERENCE_DATE:}") String gmailReference,
        @Value("${WHATSAPP_SITE_TEAM_GROUP:}") String siteGroup){
        this.repository=repository;this.salesSnapshotComplete=salesSnapshotComplete;this.clock=clock;this.policy=policy;this.reference=reference;this.gmailReference=gmailReference;this.siteGroup=siteGroup;
        if(!reference.isBlank())Instant.parse(reference);if(!gmailReference.isBlank())LocalDate.parse(gmailReference);
        if(!siteGroup.isBlank() && !WhatsAppPolicy.syntheticId(siteGroup))throw new IllegalArgumentException("Invalid site-team group configuration");
    }
    private static final Pattern SALES=Pattern.compile("(?i)\\b(?:confirmed sale|confirmed invoice|sale confirmed|invoice issued)\\b");
    private static final Pattern INVOICE=Pattern.compile("(?i)\\binvoice\\s+(?:issued\\s+)?([A-Z0-9][A-Z0-9_-]{0,63})\\b");
    private static final Pattern DATE=Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern MONEY=Pattern.compile("\\b(USD|INR|EUR|GBP)\\s+([0-9]+(?:\\.[0-9]{1,2})?)(?!\\d|,|\\.\\d)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATIVE=Pattern.compile("(?i)\\b(quote|quotation|proposal|draft|cancelled|canceled|refund|not|never|unconfirmed|reversed)\\b");
    private record Span(SearchRecord record,int start,int end){}
    private record Fact(String invoice,LocalDate date,String currency,BigDecimal amount,Span span){}
    private static final class Uncertain extends RuntimeException {}
    private Instant now(){return reference.isBlank()?clock.instant():Instant.parse(reference);}
    public QuestionAnswer answer(String question,QuestionInterpreter.Plan plan){
        String q=question.toLowerCase(Locale.ROOT).replaceAll("[?!.]$","").strip().replaceAll("\\s+"," ");
        boolean sales=q.matches("(?:what (?:were|was|are) (?:our |the )?(?:total )?sales(?: total)? (?:last month|in the previous month)|how much (?:did we sell|were (?:our )?sales) last month|(?:show|calculate|give me) (?:our |the )?(?:total )?sales (?:for |in )?(?:last month|the previous month))(?: (?:in|from) (?:gmail|drive))?");
        boolean pending=q.matches("(?:(?:which|show|list) (?:our |the )?sales emails (?:are )?(?:still )?(?:pending (?:a )?(?:reply|response)|awaiting (?:a )?(?:reply|response)|unanswered)|(?:show|list) (?:our |the )?(?:unanswered|pending) sales emails)(?: in gmail)?");
        boolean agreements=q.matches("(?:what did (?:the )?site team agree on|what did (?:the )?site team decide(?: on)?|what (?:decisions|agreements) did (?:the )?site team make|(?:show|list) (?:the )?site team (?:decisions|agreements))(?: in (?:the )?whatsapp group| on whatsapp| in whatsapp)? this week");
        if(!sales && !pending && !agreements)return null;
        Instant from=null,to=null;String classification=null;Set<String> sources=plan.sources();
        try {
            if(sales){var month=LocalDate.ofInstant(now(),ZoneOffset.UTC).withDayOfMonth(1);from=month.minusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();to=month.atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
                if(sources.contains("WHATSAPP"))throw new Uncertain();return sales(sources,from,to);}
            if(pending){classification="sales";to=(gmailReference.isBlank()?LocalDate.ofInstant(now(),ZoneOffset.UTC):LocalDate.parse(gmailReference)).atStartOfDay(ZoneOffset.UTC).toInstant();return pending(to);}
            from=LocalDate.ofInstant(now(),ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant();to=now();return agreements(from,to);
        }catch(Uncertain | DateTimeException e){return result(List.of(),"The answer cannot be determined: evidence is missing, ambiguous, conflicting or exceeds the supported bound.",sources,classification,from,to);}
    }
    private List<SearchRecord> rows(String source,String group){
        var rows=repository.search(new UnifiedRepository.Query(null,source,null,null,null,group,null,null,null,100,0));
        if(rows.size()==100)throw new Uncertain();return rows;
    }
    private void check(SearchRecord r){if(!QuestionAnswerService.valid(r) || r.source().contentText().length()>100000 || QuestionAnswerService.INJECTION.matcher(r.source().contentText()).find() || QuestionAnswerService.INJECTION.matcher(Objects.toString(r.source().title(),"")).find())throw new Uncertain();}
    private List<Span> lines(SearchRecord r){
        List<Span> result=new ArrayList<>();var m=Pattern.compile("[^\\r\\n]+").matcher(r.source().contentText());while(m.find()){if(m.end()-m.start()>600)throw new Uncertain();result.add(new Span(r,m.start(),m.end()));}return result;
    }
    private String text(Span span){return span.record().source().contentText().substring(span.start(),span.end());}
    private QuestionAnswer sales(Set<String> requested,Instant from,Instant to){
        if(!salesSnapshotComplete)throw new Uncertain();
        Set<String> sources=requested.isEmpty()?new LinkedHashSet<>(List.of("GMAIL","DRIVE")):requested;Map<String,Fact> facts=new TreeMap<>();
        for(String source:sources)for(var r:rows(source,null)){
            if("LIMITED_VERIFICATION".equals(r.details().get("coverage")) || Boolean.TRUE.equals(r.details().get("incomplete_search")) || (r.details().containsKey("extraction_status") && !"EXTRACTED".equals(r.details().get("extraction_status"))))throw new Uncertain();
            String body=r.source().contentText();String context=Objects.toString(r.source().title(),"")+" "+body;boolean signal=SALES.matcher(body).find();
            if(!signal){
                // Do not silently omit an invoice with unknown status/amount/date from a purported total.
                if(Pattern.compile("(?i)\\b(invoice|sale|sales)\\b").matcher(context).find() && !NEGATIVE.matcher(context).find())throw new Uncertain();
                continue;
            }
            check(r);
            for(var span:lines(r)){
                String line=text(span);if(!SALES.matcher(line).find())continue;if(NEGATIVE.matcher(line).find())throw new Uncertain();
                var id=INVOICE.matcher(line);var date=DATE.matcher(line);var money=MONEY.matcher(line);
                if(!id.find() || !date.find() || !money.find())throw new Uncertain();
                String invoice=id.group(1).toUpperCase(Locale.ROOT);if(Set.of("DATED","ISSUED","TOTAL","AMOUNT").contains(invoice) || !Pattern.compile("(?i)\\bdated\\s+"+Pattern.quote(date.group())).matcher(line).find())throw new Uncertain();LocalDate businessDate=LocalDate.parse(date.group());String currency=money.group(1).toUpperCase(Locale.ROOT);BigDecimal amount=new BigDecimal(money.group(2));
                if(id.find() || date.find() || money.find() || amount.signum()<0 || amount.precision()>18)throw new Uncertain();
                var f=new Fact(invoice,businessDate,currency,amount,span);var prior=facts.putIfAbsent(invoice,f);
                if(prior!=null && (!prior.date().equals(f.date()) || !prior.currency().equals(f.currency()) || prior.amount().compareTo(f.amount())!=0))throw new Uncertain();
            }
        }
        List<Fact> relevant=facts.values().stream().filter(f->{Instant date=f.date().atStartOfDay(ZoneOffset.UTC).toInstant();return !date.isBefore(from)&&!date.isAfter(to);}).toList();
        if(relevant.isEmpty() || relevant.size()>8 || relevant.stream().map(Fact::currency).distinct().count()!=1)throw new Uncertain();
        BigDecimal sum=relevant.stream().map(Fact::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        String summary="Confirmed sales in the available indexed snapshot: "+relevant.getFirst().currency()+" "+sum.stripTrailingZeros().toPlainString()+". UTC business-date interval "+from+" through "+to+". Each distinct explicit invoice counted once; this is not a complete company ledger.";
        return result(relevant.stream().map(Fact::span).toList(),summary,sources,null,from,to);
    }
    private QuestionAnswer pending(Instant cutoff){
        List<SearchRecord> rows=rows("GMAIL",null);List<Span> spans=new ArrayList<>();
        for(var r:rows){
            if(!"sales".equals(r.classification()) || !Boolean.TRUE.equals(r.details().get("inbound")))continue;
            check(r);Instant at=r.source().occurredAt();if(at==null)throw new Uncertain();if(at.isAfter(cutoff.minus(Duration.ofDays(5))))continue;
            if(!cutoff.toString().substring(0,10).equals(r.details().get("reference_date")) || !Boolean.TRUE.equals(r.details().get("unanswered_five_days"))) {
                // A false flag is legitimate for replied threads, but cannot override missing snapshot provenance.
                if(!cutoff.toString().substring(0,10).equals(r.details().get("reference_date")))throw new Uncertain();
                continue;
            }
            boolean replied=false;
            for(var other:rows)if(Objects.equals(r.source().connectorAccountId(),other.source().connectorAccountId()) && Objects.equals(r.source().parentExternalId(),other.source().parentExternalId())){
                Instant sent=other.source().occurredAt();Object labels=other.source().metadata().get("labels");
                if(labels instanceof Collection<?> c && c.contains("SENT") && sent!=null && sent.isAfter(at) && !sent.isAfter(cutoff))replied=true;
            }
            if(!replied){if(r.source().parentExternalId()==null || r.source().contentText().isBlank())throw new Uncertain();var line=lines(r);if(line.isEmpty())throw new Uncertain();spans.add(line.getFirst());}
        }
        if(spans.isEmpty() || spans.size()>8)throw new Uncertain();
        return result(spans,"Sales emails pending a reply in the indexed snapshot at "+cutoff+": inbound, at least five days old, with no recorded account reply at that cutoff. Limited exports do not establish mailbox-wide completeness.",Set.of("GMAIL"),"sales",null,cutoff);
    }
    private QuestionAnswer agreements(Instant from,Instant to){
        if(siteGroup.isBlank() || !policy.selected(siteGroup))throw new Uncertain();
        List<Span> evidence=new ArrayList<>();Map<String,String> decisions=new HashMap<>();
        for(var r:rows("WHATSAPP",siteGroup)){
            Instant at=r.source().occurredAt();if(at==null)throw new Uncertain();if(at.isBefore(from)||at.isAfter(to))continue;
            check(r);
            for(var span:lines(r)){
                String line=text(span);
                // Explicit decision key enables deterministic conflict detection. Proposals/negations are never agreements.
                var key=Pattern.compile("(?i)^Decision ([A-Z0-9_-]+): (?:we agreed to|the team agreed to) (.+)$").matcher(line);
                if(!key.matches()){if(Pattern.compile("(?i)^Decision [A-Z0-9_-]+:").matcher(line).find())throw new Uncertain();continue;}if(NEGATIVE.matcher(line).find())throw new Uncertain();
                String id=key.group(1).toUpperCase(Locale.ROOT),fact=key.group(2).strip().toLowerCase(Locale.ROOT);String old=decisions.putIfAbsent(id,fact);
                if(old!=null && !old.equals(fact))throw new Uncertain();if(old==null)evidence.add(span);
            }
        }
        if(evidence.isEmpty() || evidence.size()>8)throw new Uncertain();
        return result(evidence,"Explicit Site team agreements recorded in the configured selected WhatsApp group, UTC "+from+" through "+to+". These are attributed message statements, not inferred consensus or invented participant names.",Set.of("WHATSAPP"),null,from,to);
    }
    private QuestionAnswer result(List<Span> spans,String summary,Set<String> sources,String category,Instant from,Instant to){
        List<QuestionAnswer.Evidence> evidence=new ArrayList<>();for(var span:spans){check(span.record());var s=span.record().source();var c=span.record().citation();evidence.add(new QuestionAnswer.Evidence("S"+(evidence.size()+1),s.sourceType(),s.externalId(),s.connectorAccountId(),s.documentId(),s.sourceVersion(),c.locator(),c.sourceUrl(),span.record().dataMode(),s.title(),span.start(),span.end(),text(span)));}
        String answer=summary+(evidence.isEmpty()?"":"\n"+String.join("\n",evidence.stream().map(e->"["+e.reference()+"] "+e.excerpt()).toList()));
        return new QuestionAnswer(evidence.isEmpty()?"INSUFFICIENT_EVIDENCE":"EVIDENCE_FOUND","DETERMINISTIC_EXTRACTIVE",false,false,answer,List.copyOf(evidence),List.copyOf(sources),category,from==null?null:from.toString(),to==null?null:to.toString(),true,List.of("Bounded deterministic rules, UTC intervals, at most 99 visible records per source and eight contributing facts.","Totals require explicit confirmed-sale invoice ID, ISO business date and one ISO-currency amount per line; decisions require explicit Decision ID and agreement wording.","Indexed snapshots may be incomplete or stale. No live permission refresh, organiser data, LLM or general language understanding is claimed."));
    }
}
