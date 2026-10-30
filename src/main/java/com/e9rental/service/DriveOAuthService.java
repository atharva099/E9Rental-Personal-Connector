package com.e9rental.service;
import com.e9rental.connector.GoogleTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class DriveOAuthService extends OAuthService {
    public static final String DRIVE_SCOPE="https://www.googleapis.com/auth/drive.readonly";
    public DriveOAuthService(GoogleTransport transport,ObjectMapper json,Clock clock,
            @Value("${GOOGLE_CLIENT_ID:}") String clientId,@Value("${GOOGLE_CLIENT_SECRET:}") String clientSecret,
            @Value("${DRIVE_REDIRECT_URI:http://127.0.0.1:8080/api/drive/oauth/callback}") String redirect,
            @Value("${TOKEN_ENCRYPTION_KEY:}") String key,
            @Value("${DRIVE_TOKEN_FILE:${PERSONAL_RUNTIME_DIR:D:/E9Rental-Secrets/gmail-runtime}/drive-token.enc}") String tokenFile) {
        super(transport,new EncryptedTokenStore(key,tokenFile),json,clock,clientId,clientSecret,redirect,DRIVE_SCOPE,"/api/drive/oauth/callback");
    }
}
