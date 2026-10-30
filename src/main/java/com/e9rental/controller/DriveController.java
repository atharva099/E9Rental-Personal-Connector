package com.e9rental.controller;
import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.service.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/drive")
public class DriveController {
    private final DriveOAuthService oauth;private final DriveExportService export;private final GoogleSandboxPolicy sandbox;
    public DriveController(DriveOAuthService oauth,DriveExportService export,GoogleSandboxPolicy sandbox) {this.oauth=oauth;this.export=export;this.sandbox=sandbox;}
    @PostMapping("/oauth/start") public Map<String,String> start() {sandbox.requireDrive();return Map.of("authorization_url",oauth.begin());}
    @GetMapping("/oauth/callback") public Map<String,String> callback(@RequestParam(required=false) String state,
            @RequestParam(required=false) String code,@RequestParam(required=false) String error) {
        sandbox.requireDrive();oauth.callback(state,code,error);return Map.of("status","Drive authorized; run the protected limited Drive export");
    }
    @PostMapping("/export") public Map<String,Object> export(@RequestParam(defaultValue="5") int maxFiles) {
        sandbox.requireDrive();
        var result=export.export(maxFiles);
        return Map.of("status","EXPORTED","data_mode",result.dataMode(),"coverage",result.coverage(),
            "file_count",result.fileCount(),"extracted_count",result.extractedCount(),"incomplete_search",result.incompleteSearch());
    }
}
