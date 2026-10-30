package com.e9rental.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Transient bounded search results; contains no Gmail message or account identifiers. */
public record GmailSearchResponse(List<GmailSearchResult> results,@JsonProperty("partial_results") boolean partialResults,
        @JsonProperty("coverage_warning") String coverageWarning) {
    public GmailSearchResponse { results=List.copyOf(results); }
}
