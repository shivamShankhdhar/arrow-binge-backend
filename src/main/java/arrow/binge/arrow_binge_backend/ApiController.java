package arrow.binge.arrow_binge_backend;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/v1")
public class ApiController {
 private final AuthService auth;private final SaveService saves;private final EventService events;
 public ApiController(AuthService auth,SaveService saves,EventService events){this.auth=auth;this.saves=saves;this.events=events;}
 public record Login(UUID challengeId,String idToken,UUID deviceId){}
 public record Refresh(String refreshToken,UUID requestId){}
 public record Reset(long epoch){}
 @org.springframework.beans.factory.annotation.Value("${app.google-client-id}") private String googleClientId;
 @GetMapping("/auth/config") public Object authConfig(){return Map.of("webClientId",googleClientId,"configured",!googleClientId.isBlank());}
 @PostMapping("/auth/challenge") public Object challenge(){return auth.challenge();}
 @PostMapping("/auth/google") public Object login(@RequestBody Login r){if(r.challengeId()==null||r.deviceId()==null||r.idToken()==null||r.idToken().length()>20000)throw new ApiException(400,"INVALID_LOGIN");return auth.login(r.challengeId(),r.idToken(),r.deviceId());}
 @PostMapping("/auth/refresh") public Object refresh(@RequestBody Refresh r){if(r.requestId()==null)throw new ApiException(400,"INVALID_REFRESH");var result=auth.refresh(r.refreshToken(),r.requestId());if(result.containsKey("code"))throw new ApiException(401,"SESSION_REUSED");return result;}
 @PostMapping("/auth/logout") public Object logout(@RequestAttribute("principal") AuthService.Principal p){auth.logout(p);return Map.of("ok",true);}
 @GetMapping("/me") public Object profile(@RequestAttribute("principal") AuthService.Principal p){return auth.profile(p.userId());}
 @GetMapping("/me/sessions") public Object sessions(@RequestAttribute("principal") AuthService.Principal p){return auth.sessions(p);}
 @DeleteMapping("/me/sessions/{id}") public Object revoke(@RequestAttribute("principal") AuthService.Principal p,@PathVariable UUID id){auth.revoke(p,id);return Map.of("ok",true);}
 @GetMapping("/save") public Object save(@RequestAttribute("principal") AuthService.Principal p){return saves.read(p);}
 @PostMapping("/save/sync") public Object sync(@RequestAttribute("principal") AuthService.Principal p,@RequestBody SaveService.Snapshot snapshot){return saves.sync(p,snapshot);}
 @PostMapping("/save/reset") public Object reset(@RequestAttribute("principal") AuthService.Principal p,@RequestBody Reset r){auth.recent(p);return saves.reset(p,r.epoch());}
 @GetMapping("/me/export") public Object export(@RequestAttribute("principal") AuthService.Principal p){auth.recent(p);return Map.of("profile",auth.profile(p.userId()),"save",saves.read(p),"sessions",auth.sessions(p),"leaderboard",leaderboardExport(p),"events",events.export(p));}
 @DeleteMapping("/me") public Object delete(@RequestAttribute("principal") AuthService.Principal p){auth.delete(p);return Map.of("ok",true);}
 private Object leaderboardExport(AuthService.Principal p) {return java.util.Map.of("profile",saves.leaderboardProfile(p));}
}
