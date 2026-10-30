package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.service.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class DriveOAuthTest {
    @TempDir Path temp;GoogleTransport transport=mock(GoogleTransport.class);
    DriveOAuthService oauth() {
        return new DriveOAuthService(transport,Fixtures.JSON,Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),"synthetic-client","synthetic-secret",
            "http://127.0.0.1:8080/api/drive/oauth/callback",Base64.getEncoder().encodeToString(new byte[32]),temp.resolve("drive.enc").toString());
    }
    Map<String,String> params(String url) {
        Map<String,String> result=new HashMap<>();
        for(String pair:URI.create(url).getRawQuery().split("&")) {var p=pair.split("=",2);result.put(p[0],URLDecoder.decode(p[1],StandardCharsets.UTF_8));}return result;
    }
    @Test void requestsOnlyDriveScopeWithSeparateCallbackAndFreshConsent() {
        var p=params(oauth().begin());assertThat(p.get("scope")).isEqualTo(DriveOAuthService.DRIVE_SCOPE);
        assertThat(p.get("redirect_uri")).endsWith("/api/drive/oauth/callback");assertThat(p.get("prompt")).isEqualTo("consent");
        assertThat(p.get("include_granted_scopes")).isEqualTo("false");assertThat(p.get("code_challenge_method")).isEqualTo("S256");
    }
    @Test void driveConsentSavesSeparateEncryptedGrantWithoutTouchingGmail() throws Exception {
        var gmail=temp.resolve("gmail.enc");Files.writeString(gmail,"synthetic-gmail-sentinel");
        when(transport.token(any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"drive-access-fixture\",\"refresh_token\":\"drive-refresh-fixture\",\"scope\":\""+DriveOAuthService.DRIVE_SCOPE+"\"}"));
        var oauth=oauth();oauth.callback(params(oauth.begin()).get("state"),"code",null);
        assertThat(oauth.accessToken()).isEqualTo("drive-access-fixture");
        assertThat(Files.readString(gmail)).isEqualTo("synthetic-gmail-sentinel");
        assertThat(Files.readAllBytes(temp.resolve("drive.enc"))).isNotEmpty();
    }
    @Test void rejectsCombinedScopesAndForgedOrReplayedState() throws Exception {
        var oauth=oauth();assertThatThrownBy(()->oauth.callback("forged","code",null)).hasMessageContaining("state");
        when(transport.token(any())).thenReturn(Fixtures.JSON.readTree("{\"access_token\":\"a\",\"refresh_token\":\"r\",\"scope\":\""+DriveOAuthService.DRIVE_SCOPE+" "+OAuthService.SCOPE+"\"}"));
        String state=params(oauth.begin()).get("state");
        assertThatThrownBy(()->oauth.callback(state,"code",null)).hasMessageContaining("only drive.readonly");
        assertThatThrownBy(()->oauth.callback(state,"code",null)).hasMessageContaining("state");
    }
}
