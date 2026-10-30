package com.e9rental.service;
import com.e9rental.model.*;
import com.e9rental.model.WhatsAppOutput.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;
/** Extractive interpretation only: tasks and summaries quote exact complete source lines. */
@Service
public class WhatsAppSummaryService {
    private static final Pattern COMMITMENT=Pattern.compile("(?i)\\b(i|we)\\s+(will|shall|commit|promise)\\b|\\b(agreed|committed)\\b");
    private static final Pattern ACTION=Pattern.compile("(?i)\\b(action|todo|please|need to|must)\\b");
    private static final Pattern REQUEST=Pattern.compile("(?i)\\b(please|request|asking for|can you|could you|would you)\\b");
    private static final Pattern NEGATION=Pattern.compile("(?i)\\b(not|never|cancelled|canceled|withdrawn|proposal|propose|maybe)\\b|\\?$");
    private static final Pattern DECISION=Pattern.compile("(?i)^Decision ([A-Z0-9_-]+): (?:we agreed to|the team agreed to) (.+)$");
    public List<GroupSummary> summarize(List<WhatsAppMessage> messages){return summarize(messages,Map.of(),new TreeSet<>(messages.stream().map(WhatsAppMessage::groupId).toList()));}
    public List<GroupSummary> summarize(List<WhatsAppMessage> messages,Map<String,WhatsAppGroupMetadata> metadata,Set<String> selected){
        Map<String,List<WhatsAppMessage>> groups=new TreeMap<>();for(String id:selected)groups.put(id,new ArrayList<>());
        for(var m:messages)if(selected.contains(m.groupId()))groups.get(m.groupId()).add(m);
        List<GroupSummary> output=new ArrayList<>();
        groups.forEach((id,items)->{
            items.sort(Comparator.comparing(WhatsAppMessage::timestamp).thenComparing(WhatsAppMessage::messageId));
            List<Evidence> candidates=new ArrayList<>(),commitments=new ArrayList<>(),actions=new ArrayList<>();List<Interpretation> all=new ArrayList<>();Map<String,Set<String>> decisionValues=new HashMap<>();
            for(var m:items){var lines=Pattern.compile("[^\\r\\n]+").matcher(m.content());while(lines.find()){
                String text=lines.group();if(text.isBlank() || text.length()>600)continue;var evidence=new Evidence(text,List.of(m.messageId()),m.senderId(),lines.start(),lines.end());candidates.add(evidence);
                boolean negative=NEGATION.matcher(text).find();
                if(REQUEST.matcher(text).find() && !Pattern.compile("(?i)\\b(do not|don't|cancelled|canceled)\\b").matcher(text).find())all.add(fact("REQUEST",m,text,lines.start(),lines.end(),null,"EXPLICIT"));
                if(COMMITMENT.matcher(text).find() && !negative){commitments.add(evidence);all.add(fact("COMMITMENT",m,text,lines.start(),lines.end(),Pattern.compile("(?i)^I (?:will|shall|commit|promise)\\b").matcher(text).find()?m.senderId():null,"EXPLICIT"));}
                if(ACTION.matcher(text).find() && !negative){actions.add(evidence);all.add(fact("ACTION",m,text,lines.start(),lines.end(),null,"REQUESTED"));}
                var key=Pattern.compile("(?i)^Decision ([A-Z0-9_-]+):").matcher(text);if(key.find()){
                    String decisionId=key.group(1).toUpperCase(Locale.ROOT);var confirmed=DECISION.matcher(text);boolean explicit=confirmed.matches() && !negative;
                    decisionValues.computeIfAbsent(decisionId,k->new HashSet<>()).add(explicit?confirmed.group(2).strip().toLowerCase(Locale.ROOT):"UNCONFIRMED:"+text.toLowerCase(Locale.ROOT));
                    all.add(fact("DECISION",m,text,lines.start(),lines.end(),null,explicit?"RECORDED_AGREEMENT":"UNCONFIRMED"));
                }
            }}
            List<Interpretation> interpreted=new ArrayList<>();for(var f:all){
                if(f.kind().equals("DECISION")){var key=Pattern.compile("(?i)^Decision ([A-Z0-9_-]+):").matcher(f.text());key.find();if(decisionValues.get(key.group(1).toUpperCase(Locale.ROOT)).size()>1)f=new Interpretation(f.kind(),f.senderId(),f.task(),f.assigneeId(),f.dueDate(),"CONFLICTING",f.startOffset(),f.endOffset(),f.text(),f.supportingMessageIds());}interpreted.add(f);
            }
            var roster=new TreeMap<String,WhatsAppGroupMetadata.Participant>();var supplied=metadata.get(id);if(supplied!=null)for(var p:supplied.participants())roster.put(p.senderId(),p);
            for(var m:items)roster.putIfAbsent(m.senderId(),new WhatsAppGroupMetadata.Participant(m.senderId(),null,null));
            List<ParticipantInterpretation> participants=new ArrayList<>();for(String sender:roster.keySet())participants.add(new ParticipantInterpretation(sender,of(interpreted,"REQUEST",sender),of(interpreted,"COMMITMENT",sender),of(interpreted,"ACTION",sender),of(interpreted,"DECISION",sender)));
            // A short extractive summary: three complete evidence lines, never a clipped/generated factual sentence.
            var shortSummary=candidates.stream().limit(3).toList();
            output.add(new GroupSummary(id,items.size(),shortSummary,List.copyOf(commitments),List.copyOf(actions),supplied==null?null:supplied.groupName(),supplied==null?null:supplied.version(),supplied==null?null:supplied.updatedAt(),List.copyOf(roster.values()),of(interpreted,"REQUEST",null),of(interpreted,"DECISION",null),List.copyOf(participants)));
        });output.sort(Comparator.comparing((GroupSummary g)->g.messageCount()==0).thenComparing(GroupSummary::groupId));return List.copyOf(output);
    }
    private List<Interpretation> of(List<Interpretation> all,String kind,String sender){return all.stream().filter(f->f.kind().equals(kind) && (sender==null || sender.equals(f.senderId()))).toList();}
    private Interpretation fact(String kind,WhatsAppMessage m,String text,int start,int end,String assignee,String status){
        var dates=Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b").matcher(text);String due=null;if(dates.find()){String first=dates.group();if(!dates.find() && Pattern.compile("(?i)\\b(?:by|due(?: on)?)\\s+"+Pattern.quote(first)).matcher(text).find())try{due=LocalDate.parse(first).toString();}catch(DateTimeException ignored){}}
        return new Interpretation(kind,m.senderId(),text,assignee,due,status,start,end,text,List.of(m.messageId()));
    }
}
