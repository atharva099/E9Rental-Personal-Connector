package com.e9rental.config;

import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class PersonalModeGuardTest {
    private MockEnvironment valid() {
        Path root=Path.of("target","personal-mode-test",UUID.randomUUID().toString()).toAbsolutePath();
        String key=Base64.getEncoder().encodeToString(new byte[32]);
        String tenant="personal-"+UUID.randomUUID().toString().replace("-","");
        return new MockEnvironment()
            .withProperty("GOOGLE_ACCESS_MODE","PERSONAL")
            .withProperty("PERSONAL_GOOGLE_ENABLED","true")
            .withProperty("PERSONAL_GOOGLE_APPROVED","true")
            .withProperty("SANDBOX_GOOGLE_APPROVED","false")
            .withProperty("PERSONAL_DRIVE_ENABLED","true")
            .withProperty("PERSONAL_GMAIL_ACCOUNT","owner@example.test")
            .withProperty("PERSONAL_DRIVE_ACCOUNT_ID","synthetic-drive-owner")
            .withProperty("PERSONAL_DRIVE_ROOT_FOLDER_ID","synthetic-personal-root")
            .withProperty("GOOGLE_CLIENT_ID","synthetic-client-id")
            .withProperty("PERSONAL_PATH_VALIDATION_PASSED","true")
            .withProperty("PERSONAL_RUNTIME_DIR",root.toString())
            .withProperty("CHALLENGE_RUNTIME_DIR",root.resolveSibling("challenge-runtime").toString())
            .withProperty("PERSONAL_TENANT_ID",tenant)
            .withProperty("PRACTICE_TENANT_ID",tenant)
            .withProperty("CHALLENGE_TENANT_ID","practice-local")
            .withProperty("TOKEN_ENCRYPTION_KEY",key)
            .withProperty("PERSONAL_TOKEN_ENCRYPTION_KEY",key)
            .withProperty("LOCAL_API_TOKEN","synthetic-personal-api-token-for-test")
            .withProperty("PERSONAL_LOCAL_API_TOKEN","synthetic-personal-api-token-for-test")
            .withProperty("GMAIL_TOKEN_FILE",root.resolve("gmail-token.enc").toString())
            .withProperty("DRIVE_TOKEN_FILE",root.resolve("drive-token.enc").toString())
            .withProperty("GMAIL_OUTPUT_FILE",root.resolve("outputs/gmail.json").toString())
            .withProperty("DRIVE_OUTPUT_FILE",root.resolve("outputs/drive.json").toString())
            .withProperty("WHATSAPP_OUTPUT_FILE",root.resolve("outputs/whatsapp.json").toString())
            .withProperty("UNIFIED_STORE_URL","jdbc:h2:file:"+root.resolve("unified/index").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE")
            .withProperty("WHATSAPP_STORE_URL","jdbc:h2:file:"+root.resolve("whatsapp/messages").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE")
            .withProperty("WHATSAPP_SELECTED_GROUPS","")
            .withProperty("WHATSAPP_GROUP_CONFIG_FILE","");
    }

    @Test void acceptsCompleteExplicitlyApprovedIsolatedPersonalConfiguration(){
        assertThatCode(()->new PersonalModeGuard(valid())).doesNotThrowAnyException();
    }
    @Test void acceptsGmailOnlyStartupWithoutDriveIdentifiersTokensOrExportPath(){
        var env=valid().withProperty("PERSONAL_DRIVE_ENABLED","false")
            .withProperty("PERSONAL_DRIVE_ACCOUNT_ID","").withProperty("PERSONAL_DRIVE_ROOT_FOLDER_ID","")
            .withProperty("DRIVE_TOKEN_FILE","").withProperty("DRIVE_OUTPUT_FILE","");
        assertThatCode(()->new PersonalModeGuard(env)).doesNotThrowAnyException();
    }
    @Test void missingGmailIdentityStillFailsClosed(){
        var env=valid().withProperty("PERSONAL_GMAIL_ACCOUNT","");
        assertThatThrownBy(()->new PersonalModeGuard(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingOrBlankChallengeRuntimeFailsClosed(){
        var env=valid();
        assertThatThrownBy(()->PersonalModeGuard.validate(key->"CHALLENGE_RUNTIME_DIR".equals(key)?null:env.getProperty(key))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new PersonalModeGuard(env.withProperty("CHALLENGE_RUNTIME_DIR"," "))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new PersonalModeGuard(env.withProperty("CHALLENGE_RUNTIME_DIR","relative/challenge-runtime"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsIdenticalPersonalAndChallengeDirectories(){
        var env=valid();
        env.setProperty("CHALLENGE_RUNTIME_DIR",env.getProperty("PERSONAL_RUNTIME_DIR"));
        assertThatThrownBy(()->new PersonalModeGuard(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void driveOptInRequiresDriveIdentityAndPrivatePaths(){
        var missingIdentity=valid().withProperty("PERSONAL_DRIVE_ACCOUNT_ID","");
        assertThatThrownBy(()->new PersonalModeGuard(missingIdentity)).isInstanceOf(IllegalStateException.class);
        var missingPath=valid().withProperty("DRIVE_TOKEN_FILE","");
        assertThatThrownBy(()->new PersonalModeGuard(missingPath)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsPersonalModeWithoutBothEnablementAndApproval(){
        var missingApproval=valid().withProperty("PERSONAL_GOOGLE_APPROVED","false");
        assertThatThrownBy(()->new PersonalModeGuard(missingApproval)).isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
        var disabled=valid().withProperty("PERSONAL_GOOGLE_ENABLED","false");
        assertThatThrownBy(()->new PersonalModeGuard(disabled)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsConflictingSandboxApprovalOrPersonalFlagsInSandbox(){
        var conflict=valid().withProperty("SANDBOX_GOOGLE_APPROVED","true");
        assertThatThrownBy(()->new PersonalModeGuard(conflict)).isInstanceOf(IllegalStateException.class);
        var enabledInSandbox=new MockEnvironment().withProperty("GOOGLE_ACCESS_MODE","SANDBOX").withProperty("PERSONAL_GOOGLE_ENABLED","true");
        assertThatThrownBy(()->new PersonalModeGuard(enabledInSandbox)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsChallengeDirectoryNestedInsidePersonalRuntime(){
        var env=valid();String root=env.getProperty("PERSONAL_RUNTIME_DIR");
        env.setProperty("CHALLENGE_RUNTIME_DIR",Path.of(root).resolve("nested").toString());
        assertThatThrownBy(()->new PersonalModeGuard(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsPersonalRuntimeNestedInsideChallengeDirectory(){
        var env=valid();Path personal=Path.of(env.getProperty("PERSONAL_RUNTIME_DIR"));
        env.setProperty("CHALLENGE_RUNTIME_DIR",personal.getParent().toString());
        assertThatThrownBy(()->new PersonalModeGuard(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsChallengeDatabaseOrTokenPaths(){
        var tokenInChallenge=valid();Path challenge=Path.of(tokenInChallenge.getProperty("CHALLENGE_RUNTIME_DIR"));
        tokenInChallenge.setProperty("GMAIL_TOKEN_FILE",challenge.resolve("gmail-token.enc").toString());
        assertThatThrownBy(()->new PersonalModeGuard(tokenInChallenge)).isInstanceOf(IllegalStateException.class);
        var memoryDatabase=valid();memoryDatabase.setProperty("UNIFIED_STORE_URL","jdbc:h2:mem:challenge");
        assertThatThrownBy(()->new PersonalModeGuard(memoryDatabase)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsSharedStorePathsAndWhatsAppSelection(){
        var sharedTokens=valid();sharedTokens.setProperty("DRIVE_TOKEN_FILE",sharedTokens.getProperty("GMAIL_TOKEN_FILE"));
        assertThatThrownBy(()->new PersonalModeGuard(sharedTokens)).isInstanceOf(IllegalStateException.class);
        var selectedGroups=valid();selectedGroups.setProperty("WHATSAPP_SELECTED_GROUPS","sim-group-rentals");
        assertThatThrownBy(()->new PersonalModeGuard(selectedGroups)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsInvalidKeyOrPersonalTenantReuse(){
        var badKey=valid();badKey.setProperty("TOKEN_ENCRYPTION_KEY","not-a-key");
        assertThatThrownBy(()->new PersonalModeGuard(badKey)).isInstanceOf(IllegalStateException.class);
        var reusedTenant=valid();reusedTenant.setProperty("PRACTICE_TENANT_ID","practice-local");
        assertThatThrownBy(()->new PersonalModeGuard(reusedTenant)).isInstanceOf(IllegalStateException.class);
    }
}
