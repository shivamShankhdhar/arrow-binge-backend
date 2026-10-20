package arrow.binge.arrow_binge_backend;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;

class GoogleIdentityTest {
    private Jwt token(String audience,String azp,String nonce) {
        var builder=Jwt.withTokenValue("signed-token").header("alg","RS256").issuer("https://accounts.google.com")
            .subject("google-subject").audience(List.of(audience)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
            .claim("name","Player").claim("nonce",nonce);
        if(azp!=null)builder.claim("azp",azp);
        return builder.build();
    }
    @Test void validWebAudienceAllowsAndroidPresenterWhenNoAndroidListWasConfigured() {
        var verifier=new GoogleIdentity("web-client","web-client",raw->token("web-client","android-client","nonce"));
        assertEquals("google-subject",verifier.verify("signed-token",Crypto.hash("nonce")).subject());
    }
    @Test void configuredAndroidAllowlistIsTrimmedAndEnforced() {
        var allowed=new GoogleIdentity("web-client"," android-client, second-android ",raw->token("web-client","android-client","nonce"));
        assertNotNull(allowed.verify("signed-token",Crypto.hash("nonce")));
        var denied=new GoogleIdentity("web-client","android-client",raw->token("web-client","unknown-client","nonce"));
        assertEquals("GOOGLE_CLIENT_NOT_ALLOWED",assertThrows(ApiException.class,()->denied.verify("signed-token",Crypto.hash("nonce"))).code);
    }
    @Test void wrongAudienceAndNonceAlwaysFailEvenWithNoOptionalAllowlist() {
        var wrongAudience=new GoogleIdentity("web-client","",raw->token("other-app","android-client","nonce"));
        assertEquals("GOOGLE_AUDIENCE_MISMATCH",assertThrows(ApiException.class,()->wrongAudience.verify("signed-token",Crypto.hash("nonce"))).code);
        var wrongNonce=new GoogleIdentity("web-client","",raw->token("web-client","android-client","replayed-nonce"));
        assertEquals("GOOGLE_NONCE_MISMATCH",assertThrows(ApiException.class,()->wrongNonce.verify("signed-token",Crypto.hash("nonce"))).code);
    }
    @Test void signatureDecoderFailureIsNeverAccepted() {
        var verifier=new GoogleIdentity("web-client","",raw->{throw new JwtException("bad signature");});
        assertEquals("GOOGLE_TOKEN_INVALID",assertThrows(ApiException.class,()->verifier.verify("forged",Crypto.hash("nonce"))).code);
    }
}
