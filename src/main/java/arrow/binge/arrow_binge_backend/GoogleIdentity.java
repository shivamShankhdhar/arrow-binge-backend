package arrow.binge.arrow_binge_backend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.jwt.*;
import java.net.URI;import java.util.*;
@Component
public class GoogleIdentity {
 private final String clientId; private final Set<String> authorizedParties;
 private final NimbusJwtDecoder decoder;
 public record Identity(String subject,String name,String email,String avatar){}
 public GoogleIdentity(@Value("${app.google-client-id}") String clientId,@Value("${app.allowed-google-client-ids:}") String parties){
  this.clientId=clientId;this.authorizedParties=new HashSet<>(Arrays.asList(parties.split(",")));this.authorizedParties.add(clientId);
  decoder=NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
  decoder.setJwtValidator(new JwtTimestampValidator());
 }
 public Identity verify(String raw,String nonceHash){
  if(clientId.isBlank())throw new ApiException(503,"GOOGLE_NOT_CONFIGURED");
  try{
   Jwt jwt=decoder.decode(raw);
   String issuer=jwt.getClaimAsString("iss");String azp=jwt.getClaimAsString("azp");String nonce=jwt.getClaimAsString("nonce");
   if(!Set.of("https://accounts.google.com","accounts.google.com").contains(issuer)||!jwt.getAudience().contains(clientId)||jwt.getExpiresAt()==null||jwt.getSubject()==null||jwt.getSubject().isBlank()||nonce==null||!Crypto.hash(nonce).equals(nonceHash)|| (azp!=null&&!authorizedParties.contains(azp)))throw new ApiException(401,"GOOGLE_TOKEN_INVALID");
   String name=Optional.ofNullable(jwt.getClaimAsString("name")).orElse("Player");
   String photo=jwt.getClaimAsString("picture");
   if(photo!=null){URI u=URI.create(photo);if(!"https".equals(u.getScheme())||u.getHost()==null||!u.getHost().endsWith(".googleusercontent.com"))photo=null;}
   return new Identity(jwt.getSubject(),name.substring(0,Math.min(200,name.length())),jwt.getClaimAsString("email"),photo);
  }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(401,"GOOGLE_TOKEN_INVALID");}
 }
}
