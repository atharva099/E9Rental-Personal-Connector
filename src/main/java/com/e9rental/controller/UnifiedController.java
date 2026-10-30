package com.e9rental.controller;
import com.e9rental.connector.UnifiedRepository;
import com.e9rental.service.UnifiedIngestionService;
import com.e9rental.model.SearchRecord;
import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.connector.GmailFailure;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/unified")
public class UnifiedController {
    private final UnifiedRepository repository;private final UnifiedIngestionService ingestion;private final GoogleSandboxPolicy sandbox;
    public UnifiedController(UnifiedRepository repository,UnifiedIngestionService ingestion){this(repository,ingestion,GoogleSandboxPolicy.denied());}
    @org.springframework.beans.factory.annotation.Autowired
    public UnifiedController(UnifiedRepository repository,UnifiedIngestionService ingestion,GoogleSandboxPolicy sandbox){
        this.repository=repository;this.ingestion=ingestion;this.sandbox=sandbox;
    }
    @PostMapping("/ingest")
    public UnifiedRepository.Result ingest(@RequestParam String source){return ingestion.ingest(source);}
    @GetMapping("/search")
    public List<SearchRecord> search(@RequestParam(required=false) String keyword,@RequestParam(required=false) String source,
        @RequestParam(required=false) String classification,@RequestParam(required=false) String account,
        @RequestParam(required=false) String participant,@RequestParam(required=false) String group,
        @RequestParam(required=false) String mode,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
        @RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset) {
        if(sandbox.personalMode() && (source==null || "GMAIL".equalsIgnoreCase(source)))throw new GmailFailure(403,
            "Locally indexed Gmail search is unavailable in Live Gmail-only Personal mode",GoogleSandboxPolicy.DENIAL);
        return repository.search(new UnifiedRepository.Query(keyword,source,classification,account,participant,group,mode,from,to,limit,offset));
    }
}
