package com.e9rental.service.qa;

import com.e9rental.model.SearchRecord;
import com.e9rental.model.GmailRetrievalMetrics;
import java.util.List;

/** Question-time, transient Gmail evidence source. Implementations must enforce access and retrieval bounds. */
public interface GmailQuestionRetriever {
    RetrievalResult retrieve(QuestionInterpreter.Plan plan);

    record RetrievalResult(List<SearchRecord> records,boolean partialResults,GmailRetrievalMetrics metrics) {
        public RetrievalResult(List<SearchRecord> records,boolean partialResults) { this(records,partialResults,null); }
        public RetrievalResult { records=List.copyOf(records); }
    }
}
