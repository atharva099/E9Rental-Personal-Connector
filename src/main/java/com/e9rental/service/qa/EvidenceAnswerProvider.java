package com.e9rental.service.qa;
import com.e9rental.model.SearchRecord;
import java.util.List;
/**
 * Future provider seam. Providers may only select exact evidence spans, never supply factual prose
 * or citation identities. No network-backed implementation or external-data authorization exists.
 */
public interface EvidenceAnswerProvider {
    record Candidate(SearchRecord record,int start,int end) {}
    String mode();
    List<Candidate> select(QuestionInterpreter.Plan plan,List<Candidate> evidence);
}
