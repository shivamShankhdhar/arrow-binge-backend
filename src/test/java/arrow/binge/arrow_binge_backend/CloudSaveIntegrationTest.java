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
 @Autowired LeaderboardService leaderboard;@Autowired AuthService auth;@Autowired SaveService saves;@Autowired JdbcTemplate db;
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

 @Test void leaderboardIsOptInAndContainsNoPrivateGoogleDetails() {
  var a=principal("private-subject");saves.sync(a,snapshot(UUID.randomUUID(),0,null,3));
  assertEquals(0,((List<?>)leaderboard.read(a).get("entries")).size());
  var joined=leaderboard.join(a,"Explorer Seven");
  String json=Json.write(joined);assertTrue(json.contains("Explorer Seven"));
  assertFalse(json.contains("player@example.test"));assertFalse(json.contains("private-subject"));
  assertEquals(3,((Map<?,?>)joined.get("me")).get("stars"));
 }
 @Test void leaderboardUpdatesOnCompletionWithoutDuplicatePointsAndResetDropsScore() {
  var a=principal("a");leaderboard.join(a,"Explorer");
  var request=snapshot(UUID.randomUUID(),0,null,3);saves.sync(a,request);saves.sync(a,request);
  assertEquals(3,((Map<?,?>)leaderboard.read(a).get("me")).get("stars"));
  saves.reset(a,0);assertEquals(0,((Map<?,?>)leaderboard.read(a).get("me")).get("stars"));
 }
 @Test void tiedPlayersShareRankAndBetterScoreChangesLeader() {
  var a=principal("a");var b=principal("b");leaderboard.join(a,"Alpha");leaderboard.join(b,"Beta");
  saves.sync(a,snapshot(UUID.randomUUID(),0,null,2));saves.sync(b,snapshot(UUID.randomUUID(),0,null,2));
  assertEquals(1L,((Map<?,?>)leaderboard.read(a).get("me")).get("rank"));
  assertEquals(1L,((Map<?,?>)leaderboard.read(b).get("me")).get("rank"));
  saves.sync(b,snapshot(UUID.randomUUID(),0,null,3));
  assertEquals(2L,((Map<?,?>)leaderboard.read(a).get("me")).get("rank"));
  assertEquals(1L,((Map<?,?>)leaderboard.read(b).get("me")).get("rank"));
 }
 @Test void leavingRankingsPreservesProgressAndDeletionRemovesPublicEntry() {
  var a=principal("a");leaderboard.join(a,"Alpha");saves.sync(a,snapshot(UUID.randomUUID(),0,null,3));
  leaderboard.leave(a);assertNull(leaderboard.read(a).get("me"));
  assertEquals(1,((List<?>)saves.read(a).get("levels")).size());
  leaderboard.join(a,"Alpha");auth.delete(a);
  assertEquals(0,db.queryForObject("SELECT count(*) FROM leaderboard_profiles",Integer.class));
 }
 @Test void invalidPublicNamesAreRejected() {
  var a=principal("a");assertThrows(ApiException.class,()->leaderboard.join(a,"<script>"));
  assertThrows(ApiException.class,()->leaderboard.join(a,"x"));
  assertThrows(ApiException.class,()->leaderboard.join(a,"a".repeat(25)));
 }
 @Test void ownRankIsReturnedOutsideTopFifty() {
  var last=principal("last");leaderboard.join(last,"My Explorer");
  for(int i=0;i<55;i++){
   UUID id=UUID.randomUUID();
   db.update("INSERT INTO users(id,google_subject,display_name) VALUES(?,?,?)",id,"filler"+i,"Private");
   db.update("INSERT INTO leaderboard_profiles(user_id,nickname,stars,completed) VALUES(?,?,?,?)",id,"Explorer "+i,3,1);
  }
  var result=leaderboard.read(last);assertEquals(50,((List<?>)result.get("entries")).size());
  assertEquals(56L,((Map<?,?>)result.get("me")).get("rank"));
 }
}
