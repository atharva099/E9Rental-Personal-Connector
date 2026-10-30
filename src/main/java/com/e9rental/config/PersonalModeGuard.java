package com.e9rental.config;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Validates the personal profile before any controller can authorize provider access or persistence. */
@Component
public final class PersonalModeGuard {
    public PersonalModeGuard(Environment environment) { validate(environment::getProperty); }

    static void validate(Function<String,String> property) {
        String mode=value(property,"GOOGLE_ACCESS_MODE","SANDBOX").toUpperCase(Locale.ROOT);
        boolean enabled=truth(property,"PERSONAL_GOOGLE_ENABLED");
        boolean approved=truth(property,"PERSONAL_GOOGLE_APPROVED");
        if(!Set.of("SANDBOX","PERSONAL").contains(mode))fail();
        if(!mode.equals("PERSONAL")) {
            if(enabled || approved)fail();
            return;
        }
        if(!enabled || !approved || truth(property,"SANDBOX_GOOGLE_APPROVED"))fail();
        if(!value(property,"SANDBOX_GMAIL_ACCOUNT","").isBlank() ||
                !value(property,"SANDBOX_DRIVE_ACCOUNT_ID","").isBlank() ||
                !value(property,"SANDBOX_DRIVE_ROOT_FOLDER_ID","").isBlank())fail();
        String gmail=value(property,"PERSONAL_GMAIL_ACCOUNT","").strip();
        String driveAccount=value(property,"PERSONAL_DRIVE_ACCOUNT_ID","").strip();
        String driveRoot=value(property,"PERSONAL_DRIVE_ROOT_FOLDER_ID","").strip();
        boolean driveEnabled=truth(property,"PERSONAL_DRIVE_ENABLED");
        if(!gmail.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))fail();
        if(driveEnabled && (!GoogleSandboxPolicy.providerId(driveAccount) || !GoogleSandboxPolicy.providerId(driveRoot) || driveRoot.equals("root")))fail();
        String personalTenant=value(property,"PERSONAL_TENANT_ID","").strip();
        String challengeTenant=value(property,"CHALLENGE_TENANT_ID","practice-local").strip();
        if(!personalTenant.startsWith("personal-") || personalTenant.length()<20 || personalTenant.equals(challengeTenant) ||
                !personalTenant.equals(value(property,"PRACTICE_TENANT_ID","").strip()))fail();
        if(!truth(property,"PERSONAL_PATH_VALIDATION_PASSED"))fail();
        if(value(property,"GOOGLE_CLIENT_ID","").isBlank() || value(property,"GOOGLE_CLIENT_ID","").equals("replace_me"))fail();

        String runtime=value(property,"PERSONAL_RUNTIME_DIR","").strip();
        String challengeRuntime=value(property,"CHALLENGE_RUNTIME_DIR","").strip();
        Path root=absolutePath(runtime), challenge=absolutePath(challengeRuntime);
        if(root==null || challenge==null || overlaps(root,challenge))fail();
        String key=value(property,"TOKEN_ENCRYPTION_KEY","");
        try { if(Base64.getDecoder().decode(key).length!=32)fail(); }
        catch(IllegalArgumentException e){fail();}
        if(!key.equals(value(property,"PERSONAL_TOKEN_ENCRYPTION_KEY","") ) ||
                !value(property,"LOCAL_API_TOKEN","").equals(value(property,"PERSONAL_LOCAL_API_TOKEN","")) ||
                value(property,"LOCAL_API_TOKEN","").length()<32 || value(property,"LOCAL_API_TOKEN","").equals("replace_with_random_token_at_least_32_characters"))fail();

        List<Path> stores=new ArrayList<>(Arrays.asList(
            path(value(property,"GMAIL_TOKEN_FILE","")),path(value(property,"GMAIL_OUTPUT_FILE","")),
            path(value(property,"WHATSAPP_OUTPUT_FILE","")),h2Path(value(property,"UNIFIED_STORE_URL","")),
            h2Path(value(property,"WHATSAPP_STORE_URL",""))));
        String driveToken=value(property,"DRIVE_TOKEN_FILE","").strip(),driveOutput=value(property,"DRIVE_OUTPUT_FILE","").strip();
        if(driveEnabled && (driveToken.isEmpty() || driveOutput.isEmpty()))fail();
        if(!driveToken.isEmpty())stores.add(path(driveToken));
        if(!driveOutput.isEmpty())stores.add(path(driveOutput));
        if(stores.stream().anyMatch(p->p==null || !p.startsWith(root) || p.equals(root)))fail();
        if(new HashSet<>(stores).size()!=stores.size())fail();
        if(!value(property,"WHATSAPP_SELECTED_GROUPS","").isBlank() || !value(property,"WHATSAPP_GROUP_CONFIG_FILE","").isBlank())fail();
    }

    private static boolean truth(Function<String,String> p,String key){return "true".equals(p.apply(key));}
    private static String value(Function<String,String> p,String key,String fallback){String v=p.apply(key);return v==null?fallback:v;}
    private static Path path(String raw){try{return raw==null || raw.isBlank()?null:Path.of(raw).toAbsolutePath().normalize();}catch(Exception e){return null;}}
    private static Path absolutePath(String raw){try{if(raw==null || raw.isBlank())return null;Path path=Path.of(raw);return path.isAbsolute()?path.normalize():null;}catch(Exception e){return null;}}
    private static Path h2Path(String raw){
        if(raw==null || !raw.startsWith("jdbc:h2:file:"))return null;
        String file=raw.substring("jdbc:h2:file:".length()).split(";",2)[0];return path(file);
    }
    private static boolean overlaps(Path a,Path b){return a.startsWith(b) || b.startsWith(a);}
    private static void fail(){throw new IllegalStateException("Personal Google profile configuration is incomplete, conflicting, or unsafe");}
}
