package com.e9rental.config;

import com.e9rental.connector.GmailFailure;
import com.e9rental.model.SearchRecord;
import com.e9rental.service.GmailAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit operator approval and exact identity checks for isolated Google profiles. */
@Component
public class GoogleSandboxPolicy {
    public static final String DENIAL="SANDBOX_BOUNDARY";
    private final String mode;
    private final boolean approved, personalDriveEnabled;
    private final String gmail,driveAccount,root;

    @org.springframework.beans.factory.annotation.Autowired
    public GoogleSandboxPolicy(@Value("${GOOGLE_ACCESS_MODE:SANDBOX}") String mode,
            @Value("${SANDBOX_GOOGLE_APPROVED:false}") String sandboxApproved,
            @Value("${SANDBOX_GMAIL_ACCOUNT:}") String sandboxGmail,
            @Value("${SANDBOX_DRIVE_ACCOUNT_ID:}") String sandboxDriveAccount,
            @Value("${SANDBOX_DRIVE_ROOT_FOLDER_ID:}") String sandboxRoot,
            @Value("${PERSONAL_GOOGLE_ENABLED:false}") String personalEnabled,
            @Value("${PERSONAL_GOOGLE_APPROVED:false}") String personalApproved,
            @Value("${PERSONAL_GMAIL_ACCOUNT:}") String personalGmail,
            @Value("${PERSONAL_DRIVE_ACCOUNT_ID:}") String personalDriveAccount,
            @Value("${PERSONAL_DRIVE_ROOT_FOLDER_ID:}") String personalRoot,
            @Value("${PERSONAL_DRIVE_ENABLED:false}") String personalDriveEnabled) {
        this.mode=mode.strip().toUpperCase(Locale.ROOT);
        boolean sandboxOk="true".equals(sandboxApproved);
        boolean personalEnabledOk="true".equals(personalEnabled);
        boolean personalApprovedOk="true".equals(personalApproved);
        if(!Set.of("SANDBOX","PERSONAL").contains(this.mode) ||
                (personalEnabledOk && !this.mode.equals("PERSONAL")) ||
                (personalApprovedOk && !personalEnabledOk) ||
                (this.mode.equals("PERSONAL") && (!personalEnabledOk || !personalApprovedOk || sandboxOk)) ||
                (this.mode.equals("SANDBOX") && personalApprovedOk))
            throw new IllegalStateException("Conflicting or unapproved Google access profile configuration");
        this.approved=this.mode.equals("PERSONAL") ? personalEnabledOk && personalApprovedOk : sandboxOk;
        this.personalDriveEnabled=this.mode.equals("PERSONAL") && "true".equals(personalDriveEnabled);
        String activeGmail=this.mode.equals("PERSONAL") ? personalGmail : sandboxGmail;
        String activeDrive=this.mode.equals("PERSONAL") ? personalDriveAccount : sandboxDriveAccount;
        String activeRoot=this.mode.equals("PERSONAL") ? personalRoot : sandboxRoot;
        this.gmail=activeGmail.strip().toLowerCase(Locale.ROOT);
        this.driveAccount=activeDrive.strip();
        this.root=activeRoot.strip();
    }

    /** Backward-compatible constructor used by the challenge's sandbox tests. */
    public GoogleSandboxPolicy(String approved,String gmail,String driveAccount,String root) {
        this("SANDBOX",approved,gmail,driveAccount,root,"false","false","","","","false");
    }
    /** Keeps legacy personal fixtures explicitly Drive-enabled; new callers must opt in. */
    public GoogleSandboxPolicy(String mode,String sandboxApproved,String sandboxGmail,String sandboxDriveAccount,String sandboxRoot,
            String personalEnabled,String personalApproved,String personalGmail,String personalDriveAccount,String personalRoot) {
        this(mode,sandboxApproved,sandboxGmail,sandboxDriveAccount,sandboxRoot,personalEnabled,personalApproved,personalGmail,personalDriveAccount,personalRoot,"true");
    }

    public static GoogleSandboxPolicy denied(){return new GoogleSandboxPolicy("false","","","");}
    public boolean gmailConfigured(){return approved && gmail.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}");}
    public boolean driveEnabled(){return !personalMode() || personalDriveEnabled;}
    public boolean driveConfigured(){return driveEnabled() && approved && providerId(driveAccount) && providerId(root) && !root.equals("root");}
    public boolean personalMode(){return mode.equals("PERSONAL");}
    public String mode(){return mode;}
    public static boolean providerId(String id){return id!=null && id.matches("[A-Za-z0-9_-]{1,200}");}
    public void requireGmail(){if(!gmailConfigured())throw new GmailFailure(503,personalMode()?"Explicitly approved personal Gmail configuration required":"Approved Gmail sandbox configuration required",DENIAL);}
    public void requireDrive(){if(!driveConfigured())throw new GmailFailure(503,personalMode()?"Explicitly approved personal Drive configuration required":"Approved Drive sandbox configuration required",DENIAL);}
    public void checkGmailAccount(String actual){requireGmail();if(actual==null || !gmail.equals(actual.toLowerCase(Locale.ROOT)))deny();}
    /** Returns only a comparison result; callers must not expose the configured or provider identity. */
    public boolean gmailAccountMatches(String actual){requireGmail();return actual!=null && gmail.equals(actual.strip().toLowerCase(Locale.ROOT));}
    public void checkDriveAccount(String actual){requireDrive();if(!driveAccount.equals(actual))deny();}
    public String root(){requireDrive();return root;}
    public String gmailRecordAccount(){return "gmail-"+GmailAnalyzer.hash(gmail).substring(0,24);}
    public String driveRecordAccount(){return "drive-"+GmailAnalyzer.hash(driveAccount).substring(0,24);}
    public void checkRoot(JsonNode file) {
        requireDrive();
        if(file==null || !root.equals(file.path("id").asText()) ||
                !"application/vnd.google-apps.folder".equals(file.path("mimeType").asText()) ||
                !file.path("trashed").isBoolean() || file.path("trashed").asBoolean() ||
                file.hasNonNull("driveId") || !file.path("parents").isArray() || file.path("parents").size()!=1 ||
                !providerId(file.path("parents").get(0).asText()) || root.equals(file.path("parents").get(0).asText()))deny();
    }
    public void checkFile(JsonNode file) {
        requireDrive();
        if(file==null || !providerId(file.path("id").asText()) || root.equals(file.path("id").asText()) ||
                !file.path("trashed").isBoolean() || file.path("trashed").asBoolean() || file.hasNonNull("driveId") ||
                file.hasNonNull("shortcutDetails") || !file.path("parents").isArray() || file.path("parents").size()!=1 ||
                !root.equals(file.path("parents").get(0).asText()) ||
                "application/vnd.google-apps.folder".equals(file.path("mimeType").asText()) ||
                "application/vnd.google-apps.shortcut".equals(file.path("mimeType").asText()))deny();
    }
    /** Applied before persistence and separately in SQL before private JSON is decoded. */
    public void checkRecord(SearchRecord r) {
        if(personalMode() && !"LIVE".equals(r.dataMode()))deny();
        if(!"LIVE".equals(r.dataMode()))return;
        var s=r.source();
        if("GMAIL".equals(s.sourceType())){requireGmail();if(!gmailRecordAccount().equals(s.connectorAccountId()))deny();}
        else if("DRIVE".equals(s.sourceType())){requireDrive();if(!driveRecordAccount().equals(s.connectorAccountId()) || !root.equals(s.parentExternalId()))deny();}
        else deny();
    }
    public static void deny(){throw new GmailFailure(403,"Sandbox access denied",DENIAL);}
}
