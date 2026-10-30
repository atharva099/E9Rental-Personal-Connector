package com.e9rental.model;
import java.util.*;
/** Source evidence is preserved rather than flattened into an unauditable text blob. */
public record SearchRecord(SourceRecord source, String dataMode, String classification,
        String participant, Map<String,Object> details, Citation citation) {
    public record Citation(String documentId,String sourceVersion,String sourceType,
            String locator,String sourceUrl) {}
}
