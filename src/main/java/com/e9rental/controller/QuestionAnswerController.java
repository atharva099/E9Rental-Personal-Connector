package com.e9rental.controller;
import com.e9rental.service.qa.QuestionAnswerService;
import com.e9rental.config.LocalApiGuard;
import com.e9rental.service.GmailMessageSelectionRegistry;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class QuestionAnswerController {
    private final QuestionAnswerService service;private final ObjectMapper parser;
    public QuestionAnswerController(QuestionAnswerService service,ObjectMapper json) {
        this.service=service;parser=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        parser.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(4096).build());
    }
    @PostMapping(value="/api/qa/ask",consumes="application/json")
    public ResponseEntity<?> ask(HttpServletRequest request) {
        JsonNode node;
        try {
            var body=request.getInputStream().readNBytes(4097);
            if(body.length>4096)return ResponseEntity.status(413).body(Map.of("error","Question payload too large"));
            node=parser.readTree(body);
        }catch(Exception e){return ResponseEntity.badRequest().body(Map.of("error","Invalid question payload"));}
        if(node==null || !node.isObject() || !node.has("question") || !node.path("question").isTextual() ||
                node.size()>(node.has("searchGmailNow")?2:1) ||
                (node.has("searchGmailNow") && !node.path("searchGmailNow").isBoolean()))throw new IllegalArgumentException();
        var scope=requestScope(request);
        return ResponseEntity.ok(service.ask(node.path("question").textValue(),node.path("searchGmailNow").asBoolean(false),scope));
    }
    private static GmailMessageSelectionRegistry.RequestScope requestScope(HttpServletRequest request) {
        String principal=(String)request.getAttribute(LocalApiGuard.PRINCIPAL_ATTRIBUTE);
        String clientSession=request.getHeader("X-Client-Session");
        if(principal==null || clientSession==null)return null; // Existing clients may search without opting into the viewer capability.
        try { return new GmailMessageSelectionRegistry.RequestScope(principal,clientSession); }
        catch(IllegalArgumentException invalid) { throw new com.e9rental.connector.GmailFailure(400,"Invalid local viewer session"); }
    }
}
