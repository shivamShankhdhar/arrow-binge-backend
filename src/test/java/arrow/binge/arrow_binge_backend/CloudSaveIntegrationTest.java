package arrow.binge.arrow_binge_backend;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.session-secret=local-test-only-secret-at-least-32-characters"})
class CloudSaveIntegrationTest {
 @Autowired AuthService auth;@Autowired SaveService saves;@Autowired JdbcTemplate db;
 @MockitoBean GoogleIdentity google;
 @BeforeEach void setup(){db.execute("TRUNCATE users,auth_challenges CASCADE");when(google.verify(anyString(),anyString())).thenAnswer(a->new GoogleIdentity.Identity(a.getArgument(0),"Player","player@example.test",null));}
 Map<String,Object> login(String subject){var challenge=auth.challenge();return auth.login((UUID)challenge.get("challengeId"),subject,UUID.randomUUID());}
 AuthService.Principal principal(String subject){return auth.authenticate((String)login(subject).get("accessToken"));}
 SaveService.Snapshot snapshot(UUID operation,long epoch,UUID guest,int stars){return new SaveService.Snapshot(operation,epoch,guest,List.of(new SaveService.Level(1,stars)),Set.of(1),List.of(),1,true,true);}
 @Test void retriesAreIdempotentAndStarsNeverDecrease(){var p=principal("a");var s=snapshot(UUID.randomUUID(),0,null,3);saves.sync(p,s);saves.sync(p,s);assertEquals(1L,((Number)saves.read(p).get("revision")).longValue());saves.sync(p,snapshot(UUID.randomUUID(),0,null,1));assertEquals(3,db.queryForObject("SELECT stars FROM level_progress WHERE user_id=?",Integer.class,p.userId()));}
 @Test void operationCannotBeReusedForDifferentPayload(){var p=principal("a");UUID op=UUID.randomUUID();saves.sync(p,snapshot(op,0,null,1));assertEquals("OPERATION_REUSED",assertThrows(ApiException.class,()->saves.sync(p,snapshot(op,0,null,3))).code);}
 @Test void accountsAreIsolated(){var a=principal("a");var b=principal("b");saves.sync(a,snapshot(UUID.randomUUID(),0,null,3));assertTrue(((List<?>)saves.read(b).get("levels")).isEmpty());}
 @Test void resetRejectsOldOfflineSaves(){var p=principal("a");saves.sync(p,snapshot(UUID.randomUUID(),0,null,3));saves.reset(p,0);assertEquals("SAVE_RESET_REQUIRED",assertThrows(ApiException.class,()->saves.sync(p,snapshot(UUID.randomUUID(),0,null,3))).code);assertTrue(((List<?>)saves.read(p).get("levels")).isEmpty());}
 @Test void guestCanOnlyBeImportedOnce(){var a=principal("a");var b=principal("b");UUID guest=UUID.randomUUID();saves.sync(a,snapshot(UUID.randomUUID(),0,guest,3));assertEquals("GUEST_ALREADY_IMPORTED",assertThrows(ApiException.class,()->saves.sync(b,snapshot(UUID.randomUUID(),0,guest,3))).code);}
 @Test void refreshRetryReturnsSameTokensAndReuseRevokes(){var login=login("a");String old=(String)login.get("refreshToken");UUID request=UUID.randomUUID();var first=auth.refresh(old,request);var retry=auth.refresh(old,request);assertEquals(first.get("refreshToken"),retry.get("refreshToken"));assertEquals("SESSION_REUSED",auth.refresh(old,UUID.randomUUID()).get("code"));assertThrows(ApiException.class,()->auth.authenticate((String)first.get("accessToken")));}
 @Test void deletionCascadesAndRevokesTokens(){var login=login("a");var p=auth.authenticate((String)login.get("accessToken"));saves.sync(p,snapshot(UUID.randomUUID(),0,null,3));auth.delete(p);assertEquals(0,db.queryForObject("SELECT count(*) FROM level_progress",Integer.class));assertThrows(ApiException.class,()->auth.authenticate((String)login.get("accessToken")));}
 @Test void attemptsRemainSeparateAndOlderCheckpointCannotReplaceNewer(){var p=principal("a");UUID run=UUID.randomUUID();var newer=new SaveService.Attempt(1,run,2,Catalog.VERSION,List.of(0,1),5,0,List.of(),false,false);var older=new SaveService.Attempt(1,run,1,Catalog.VERSION,List.of(0),5,0,List.of(),false,false);for(var attempt:List.of(newer,older))saves.sync(p,new SaveService.Snapshot(UUID.randomUUID(),0,null,List.of(),Set.of(1),List.of(attempt),1,true,true));assertEquals(2L,db.queryForObject("SELECT checkpoint FROM level_attempts",Long.class));}
 @Test void invalidArrowsAreRejected(){var p=principal("a");var bad=new SaveService.Attempt(1,UUID.randomUUID(),1,Catalog.VERSION,List.of(9999),5,0,List.of(),false,false);assertThrows(ApiException.class,()->saves.sync(p,new SaveService.Snapshot(UUID.randomUUID(),0,null,List.of(),Set.of(1),List.of(bad),1,true,true)));}
 @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
 @Test void httpChallengeIsPublicAndSaveRequiresBearer() throws Exception {
  var http=java.net.http.HttpClient.newHttpClient();
  var challenge=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/v1/auth/challenge")).POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type","application/json").build(),java.net.http.HttpResponse.BodyHandlers.ofString());
  assertEquals(200,challenge.statusCode());assertTrue(challenge.body().contains("nonce"));
  var denied=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/v1/save")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(401,denied.statusCode());
  var session=login("http-user");
  var allowed=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/v1/save")).header("Authorization","Bearer "+session.get("accessToken")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,allowed.statusCode());assertTrue(allowed.body().contains("discovery-v3"));
 }
}
