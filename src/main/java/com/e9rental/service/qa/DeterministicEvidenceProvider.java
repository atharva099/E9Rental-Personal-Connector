package com.e9rental.service.qa;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class DeterministicEvidenceProvider implements EvidenceAnswerProvider {
    public String mode(){return "DETERMINISTIC_EXTRACTIVE";}
    public List<Candidate> select(QuestionInterpreter.Plan plan,List<Candidate> evidence) {
        // Round-robin by source prevents a large mailbox from displacing all other sources.
        Map<String,ArrayDeque<Candidate>> groups=new TreeMap<>();
        for(var c:evidence)groups.computeIfAbsent(c.record().source().sourceType(),k->new ArrayDeque<>()).add(c);
        int limit=plan.gmailSearch()!=null && (plan.gmailSearch().queryKind()==com.e9rental.connector.GmailSearchPlan.QueryKind.DATE ||
            plan.gmailSearch().queryKind()==com.e9rental.connector.GmailSearchPlan.QueryKind.RECENT)?plan.gmailSearch().maxMessages():8;
        List<Candidate> result=new ArrayList<>();
        while(result.size()<limit && groups.values().stream().anyMatch(q->!q.isEmpty()))
            for(var queue:groups.values())if(!queue.isEmpty() && result.size()<limit)result.add(queue.removeFirst());
        return List.copyOf(result);
    }
}
