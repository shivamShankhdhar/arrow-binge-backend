package arrow.binge.arrow_binge_backend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.jwt.*;
import java.net.URI;import java.util.*;
@Component
public class GoogleIdentity {
 private final String clientId; private final Set<String> authorizedParties;
 private final JwtDecoder decoder;
 public record Identity(String subject,String name,String email,String avatar){}
 @org.springframework.beans.factory.annotation.Autowired
 public GoogleIdentity(@Value("${app.google-client-id}") String clientId,@Value("${app.allowed-google-client-ids:}") String parties){
  this.clientId=clientId.strip();this.authorizedParties=new HashSet<>();for(String party:parties.split(",")){if(!party.isBlank())this.authorizedParties.add(party.strip());}this.authorizedParties.add(this.clientId);
  var googleDecoder=NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
  googleDecoder.setJwtValidator(new JwtTimestampValidator());decoder=googleDecoder;
 }
 // Package-private decoder injection keeps claim tests independent of Google's network.
 GoogleIdentity(String clientId,String parties,JwtDecoder decoder) {
  this.clientId=clientId.strip();this.authorizedParties=new HashSet<>();
  for(String party:parties.split(",")){if(!party.isBlank())authorizedParties.add(party.strip());}
  this.authorizedParties.add(this.clientId);this.decoder=decoder;
 }
 public Identity verify(String raw,String nonceHash){
  if(clientId.isBlank())throw new ApiException(503,"GOOGLE_NOT_CONFIGURED");
  try{
   Jwt jwt=decoder.decode(raw);
   String issuer=jwt.getClaimAsString("iss");String azp=jwt.getClaimAsString("azp");String nonce=jwt.getClaimAsString("nonce");
   if(!Set.of("https://accounts.google.com","accounts.google.com").contains(issuer)||jwt.getExpiresAt()==null||jwt.getSubject()==null||jwt.getSubject().isBlank())throw new ApiException(401,"GOOGLE_TOKEN_INVALID");
   if(!jwt.getAudience().contains(clientId))throw new ApiException(401,"GOOGLE_AUDIENCE_MISMATCH");
   if(nonce==null||!Crypto.hash(nonce).equals(nonceHash))throw new ApiException(401,"GOOGLE_NONCE_MISMATCH");
   String projectPrefix=clientId.contains("-")?clientId.substring(0,clientId.indexOf('-')+1):"";
   if(azp!=null&&authorizedParties.size()>1&&!authorizedParties.contains(azp)&&(!projectPrefix.isBlank()&&!azp.startsWith(projectPrefix)))throw new ApiException(401,"GOOGLE_CLIENT_NOT_ALLOWED");
   String name=Optional.ofNullable(jwt.getClaimAsString("name")).orElse("Player");
   String photo=jwt.getClaimAsString("picture");
   if(photo!=null){URI u=URI.create(photo);if(!"https".equals(u.getScheme())||u.getHost()==null||!u.getHost().endsWith(".googleusercontent.com"))photo=null;}
   return new Identity(jwt.getSubject(),name.substring(0,Math.min(200,name.length())),jwt.getClaimAsString("email"),photo);
  }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(401,"GOOGLE_TOKEN_INVALID");}
 }
}
