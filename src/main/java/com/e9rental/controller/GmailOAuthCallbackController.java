package com.e9rental.controller;

import com.e9rental.service.OAuthService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Google Desktop loopback callback: the exact public route is the loopback root. */
@RestController
public class GmailOAuthCallbackController {
    private final OAuthService oauth;

    public GmailOAuthCallbackController(OAuthService oauth) { this.oauth=oauth; }

    @GetMapping("/")
    public Map<String,String> callback(@RequestParam(required=false) String state,
            @RequestParam(required=false) String code, @RequestParam(required=false) String error) {
        oauth.callback(state,code,error);
        return Map.of("status","Authorized; run the protected export endpoint to verify Gmail reads");
    }
}
