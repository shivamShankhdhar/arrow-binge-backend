package arrow.binge.arrow_binge_backend;
import org.springframework.stereotype.Service;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.annotation.Transactional;import org.springframework.beans.factory.annotation.Value;
import java.util.*;import java.time.*;import java.sql.Timestamp;
@Service
public class AuthService {
 private final JdbcTemplate db;private final GoogleIdentity google;private final String secret;
 public record Principal(UUID userId,UUID sessionId,UUID deviceId,Instant authenticatedAt){}
 public AuthService(JdbcTemplate db,GoogleIdentity google,@Value("${app.session-secret}") String secret){this.db=db;this.google=google;this.secret=secret;}
 static Timestamp time(Instant i){return Timestamp.from(i);}
 public Map<String,Object> challenge(){String nonce=Crypto.token();UUID id=UUID.randomUUID();db.update("INSERT INTO auth_challenges(id,nonce_hash,expires_at) VALUES(?,?,?)",id,Crypto.hash(nonce),time(Instant.now().plusSeconds(300)));return Map.of("challengeId",id,"nonce",nonce);}
 @Transactional public Map<String,Object> login(UUID challengeId,String token,UUID deviceId){
  if(secret.length()<32)throw new ApiException(503,"SESSION_NOT_CONFIGURED");
  var rows=db.queryForList("SELECT * FROM auth_challenges WHERE id=? AND consumed=false AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",challengeId);
  if(rows.isEmpty())throw new ApiException(401,"CHALLENGE_EXPIRED");
  var identity=google.verify(token,(String)rows.get(0).get("nonce_hash"));
  db.update("UPDATE auth_challenges SET consumed=true WHERE id=?",challengeId);
  UUID id=UUID.randomUUID();
  db.update("INSERT INTO users(id,google_subject,display_name,email,avatar_url) VALUES(?,?,?,?,?) ON CONFLICT(google_subject) DO UPDATE SET display_name=EXCLUDED.display_name,email=EXCLUDED.email,avatar_url=EXCLUDED.avatar_url,authenticated_at=CURRENT_TIMESTAMP",id,identity.subject(),identity.name(),identity.email(),identity.avatar());
  UUID user=db.queryForObject("SELECT id FROM users WHERE google_subject=?",UUID.class,identity.subject());
  UUID session=UUID.randomUUID();String access=Crypto.token(),refresh=Crypto.token();Instant now=Instant.now();
  db.update("INSERT INTO device_sessions(id,user_id,device_id,access_hash,refresh_hash,access_expires_at,refresh_expires_at,absolute_expires_at,authenticated_at) VALUES(?,?,?,?,?,?,?,?,?)",session,user,deviceId,Crypto.hash(access),Crypto.hash(refresh),time(now.plusSeconds(600)),time(now.plusSeconds(2592000)),time(now.plusSeconds(7776000)),time(now));
  return tokens(user,session,access,refresh);
 }
 private Map<String,Object> tokens(UUID user,UUID session,String access,String refresh){return Map.of("accessToken",access,"refreshToken",refresh,"expiresIn",600,"sessionId",session,"profile",profile(user));}
 public Map<String,Object> profile(UUID user){var row=db.queryForMap("SELECT id,display_name,email,avatar_url,epoch,revision FROM users WHERE id=? AND deleted_at IS NULL",user);var result=new HashMap<String,Object>();result.put("id",row.get("id"));result.put("name",row.get("display_name"));result.put("email",row.get("email"));result.put("avatarUrl",row.get("avatar_url"));result.put("epoch",row.get("epoch"));result.put("revision",row.get("revision"));return result;}
 public Principal authenticate(String token){if(token==null||token.length()>1024)throw new ApiException(401,"SIGN_IN_REQUIRED");var rows=db.query("SELECT s.* FROM device_sessions s JOIN users u ON u.id=s.user_id WHERE access_hash=? AND revoked=false AND access_expires_at>CURRENT_TIMESTAMP AND u.deleted_at IS NULL",(rs,n)->new Principal(rs.getObject("user_id",UUID.class),rs.getObject("id",UUID.class),rs.getObject("device_id",UUID.class),rs.getTimestamp("authenticated_at").toInstant()),Crypto.hash(token));if(rows.isEmpty())throw new ApiException(401,"SIGN_IN_REQUIRED");return rows.get(0);}
 @Transactional public Map<String,Object> refresh(String token,UUID request){
  if(secret.length()<32)throw new ApiException(503,"SESSION_NOT_CONFIGURED");
  if(token==null||token.length()>1024)throw new ApiException(401,"SESSION_EXPIRED");String hash=Crypto.hash(token);
  var rows=db.queryForList("SELECT * FROM device_sessions WHERE refresh_hash=? OR previous_refresh_hash=? FOR UPDATE",hash,hash);
  if(rows.isEmpty())throw new ApiException(401,"SESSION_EXPIRED");var r=rows.get(0);UUID id=(UUID)r.get("id"),user=(UUID)r.get("user_id");
  if((Boolean)r.get("revoked")||((Timestamp)r.get("refresh_expires_at")).toInstant().isBefore(Instant.now())||((Timestamp)r.get("absolute_expires_at")).toInstant().isBefore(Instant.now()))throw new ApiException(401,"SESSION_EXPIRED");
  boolean previous=hash.equals(r.get("previous_refresh_hash"));
  if(previous&&(!request.equals(r.get("refresh_request_id"))||((Timestamp)r.get("rotated_at")).toInstant().isBefore(Instant.now().minusSeconds(60)))){
   db.update("UPDATE device_sessions SET revoked=true WHERE id=?",id);return Map.of("code","SESSION_REUSED");
  }
  String refresh=Crypto.derive(secret,"refresh:"+token+":"+request),access=Crypto.derive(secret,"access:"+token+":"+request);
  if(!previous)db.update("UPDATE device_sessions SET previous_refresh_hash=refresh_hash,refresh_hash=?,access_hash=?,refresh_request_id=?,rotated_at=CURRENT_TIMESTAMP,access_expires_at=?,refresh_expires_at=? WHERE id=?",Crypto.hash(refresh),Crypto.hash(access),request,time(Instant.now().plusSeconds(600)),time(Instant.now().plusSeconds(2592000)),id);
  return tokens(user,id,access,refresh);
 }
 public void logout(Principal p){db.update("UPDATE device_sessions SET revoked=true WHERE id=?",p.sessionId());}
 public void recent(Principal p){if(p.authenticatedAt().isBefore(Instant.now().minusSeconds(300)))throw new ApiException(403,"RECENT_SIGN_IN_REQUIRED");}
 public List<Map<String,Object>> sessions(Principal p){return db.queryForList("SELECT id,device_id,created_at,authenticated_at FROM device_sessions WHERE user_id=? AND revoked=false",p.userId());}
 public void revoke(Principal p,UUID session){db.update("UPDATE device_sessions SET revoked=true WHERE id=? AND user_id=?",session,p.userId());}
 @Transactional public void delete(Principal p){recent(p);db.update("DELETE FROM users WHERE id=?",p.userId());}
}
