package com.e9rental.model;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
public record QuestionAnswer(String status,String mode,boolean llmGenerated,boolean externalProcessing,
        String answer,List<Evidence> evidence,List<String> sources,String classification,
        String from,String to,boolean limitedCoverage,List<String> limitations,boolean partialResults,
        GmailRetrievalMetrics gmailRetrieval,@JsonProperty("recent_emails") List<GmailSearchResult> recentEmails) {
    public QuestionAnswer { recentEmails=recentEmails==null?List.of():List.copyOf(recentEmails); }
    public QuestionAnswer(String status,String mode,boolean llmGenerated,boolean externalProcessing,
            String answer,List<Evidence> evidence,List<String> sources,String classification,
            String from,String to,boolean limitedCoverage,List<String> limitations,boolean partialResults,
            GmailRetrievalMetrics gmailRetrieval) {
        this(status,mode,llmGenerated,externalProcessing,answer,evidence,sources,classification,from,to,
            limitedCoverage,limitations,partialResults,gmailRetrieval,List.of());
    }
    public QuestionAnswer(String status,String mode,boolean llmGenerated,boolean externalProcessing,
            String answer,List<Evidence> evidence,List<String> sources,String classification,
            String from,String to,boolean limitedCoverage,List<String> limitations,boolean partialResults) {
        this(status,mode,llmGenerated,externalProcessing,answer,evidence,sources,classification,from,to,
            limitedCoverage,limitations,partialResults,null,List.of());
    }
    public QuestionAnswer(String status,String mode,boolean llmGenerated,boolean externalProcessing,
            String answer,List<Evidence> evidence,List<String> sources,String classification,
            String from,String to,boolean limitedCoverage,List<String> limitations) {
        this(status,mode,llmGenerated,externalProcessing,answer,evidence,sources,classification,from,to,limitedCoverage,limitations,false,null,List.of());
    }
    public record Evidence(String reference,String sourceType,String sourceId,String connectorAccountId,
        String documentId,String sourceVersion,String locator,String sourceUrl,String dataMode,String title,
        int startOffset,int endOffset,String excerpt,String viewSelectionId) {
        public Evidence(String reference,String sourceType,String sourceId,String connectorAccountId,
                String documentId,String sourceVersion,String locator,String sourceUrl,String dataMode,String title,
                int startOffset,int endOffset,String excerpt) {
            this(reference,sourceType,sourceId,connectorAccountId,documentId,sourceVersion,locator,sourceUrl,dataMode,title,
                startOffset,endOffset,excerpt,null);
        }
    }
}
