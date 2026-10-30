package com.e9rental.config;
import com.e9rental.connector.GmailFailure;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class WhatsAppPolicy {
    private final String mode,tenant,account;
    private final Set<String> groups;
    public WhatsAppPolicy(@Value("${WHATSAPP_MODE:SIMULATED}") String mode,
            @Value("${WHATSAPP_SELECTED_GROUPS:}") String selectedGroups,
            @Value("${PRACTICE_TENANT_ID:practice-local}") String tenant,
            @Value("${WHATSAPP_SIMULATED_ACCOUNT:sim-account-local}") String account) {
        this(mode,selectedGroups,tenant,account,"");
    }
    @org.springframework.beans.factory.annotation.Autowired
    public WhatsAppPolicy(@Value("${WHATSAPP_MODE:SIMULATED}") String mode,@Value("${WHATSAPP_SELECTED_GROUPS:}") String selectedGroups,
        @Value("${PRACTICE_TENANT_ID:practice-local}") String tenant,@Value("${WHATSAPP_SIMULATED_ACCOUNT:sim-account-local}") String account,
        @Value("${WHATSAPP_GROUP_CONFIG_FILE:}") String configFile){
        this.mode=mode;this.tenant=tenant;this.account=account;
        if(!configFile.isBlank())selectedGroups=readGroups(configFile);
        Set<String> allowed=new TreeSet<>();
        for(String group:selectedGroups.split(",")) {
            if(group.isBlank())continue;
            String id=group.strip();
            if(!syntheticId(id))throw new IllegalArgumentException("Invalid simulated selected_groups configuration");
            allowed.add(id);
        }
        if(allowed.size()>25 || tenant.isBlank() || tenant.length()>100 || !syntheticId(account))throw new IllegalArgumentException("Invalid simulation configuration");
        groups=Collections.unmodifiableSet(allowed);
    }
    private static String readGroups(String file){
        try{
            var path=java.nio.file.Path.of(file);if(java.nio.file.Files.size(path)>4096)throw new IllegalArgumentException();
            var parser=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            parser.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(256).build());
            var node=parser.readTree(java.nio.file.Files.readAllBytes(path));if(!node.isObject() || node.size()!=1 || !node.path("selected_groups").isArray() || node.path("selected_groups").size()>25)throw new IllegalArgumentException();
            java.util.List<String> groups=new java.util.ArrayList<>();for(var group:node.path("selected_groups")){if(!group.isTextual() || !syntheticId(group.textValue()) || groups.contains(group.textValue()))throw new IllegalArgumentException();groups.add(group.textValue());}return String.join(",",groups);
        }catch(Exception e){throw new IllegalArgumentException("Invalid simulated group configuration file");}
    }
    public static boolean syntheticId(String id){return id!=null && id.matches("sim-[A-Za-z0-9][A-Za-z0-9_.:@-]{0,123}");}
    public void requireSimulation(){if(!"SIMULATED".equals(mode))throw new GmailFailure(503,"Only simulated WhatsApp ingestion is implemented");}
    public boolean selected(String id){return groups.contains(id);}
    public Set<String> selectedGroups(){return groups;}
    public String tenant(){return tenant;}
    public String account(){return account;}
}
