package arrow.binge.arrow_binge_backend;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.session-secret=local-test-only-secret-at-least-32-characters"})
class EventIntegrationTest {
    @Autowired EventService events; @Autowired AuthService auth; @Autowired Catalog catalog; @Autowired JdbcTemplate db;
    @MockitoBean GoogleIdentity google;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @BeforeEach void setup() {
        db.execute("TRUNCATE users,auth_challenges CASCADE");
        when(google.verify(anyString(),anyString())).thenAnswer(a->new GoogleIdentity.Identity(a.getArgument(0),"Private Name","private@example.test",null));
    }
    AuthService.Principal player(String name) {
        var challenge=auth.challenge();
        return auth.authenticate((String)auth.login((UUID)challenge.get("challengeId"),name,UUID.randomUUID()).get("accessToken"));
    }
    List<Integer> ids(Map<String,Object> run) { return IntStream.range(0,catalog.count((Integer)run.get("levelId"))).boxed().toList(); }
    @Test void dailyScheduleAndWeekAreDeterministicAtUtcBoundary() {
        assertEquals(LocalDate.parse("2026-10-19"),EventSchedule.week(LocalDate.parse("2026-10-25")));
        assertEquals(LocalDate.parse("2026-10-26"),EventSchedule.week(LocalDate.parse("2026-10-26")));
        assertEquals(50,new HashSet<>(EventSchedule.tournamentLevels(LocalDate.parse("2026-10-19"))).size());
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"),EventSchedule.weekEnd(LocalDate.parse("2026-10-19")));
        for(int i=0;i<365;i++) assertTrue(catalog.count(EventSchedule.dailyLevel(LocalDate.of(2026,1,1).plusDays(i)))>0);
    }
    @Test void dailyClearIsIdempotentAndDoesNotChangeRegularProgress() {
        var p=player("daily");String day=EventSchedule.today(Instant.now()).toString();
        var run=events.start(p,"daily",day);
        assertEquals(run.get("id"),events.start(p,"daily",day).get("id"));
        events.finish(p,(UUID)run.get("id"),ids(run));events.finish(p,(UUID)run.get("id"),ids(run));
        assertEquals(List.of(day),events.mine(p).get("dailyDays"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM level_progress",Integer.class));
    }
    @Test void tournamentCountsEachBoardOnceAndAdvancesAfterAcknowledgement() {
        var p=player("t");events.join(p,"Explorer");
        var run=events.start(p,"tournament",null);
        assertEquals(run.get("id"),events.start(p,"tournament",null).get("id"));
        var receipt=events.finish(p,(UUID)run.get("id"),ids(run));
        assertEquals(receipt,events.finish(p,(UUID)run.get("id"),ids(run)));
        var me=(Map<?,?>)events.mine(p).get("me");
        assertEquals(ids(run).size(),me.get("points"));assertEquals(1,me.get("completed"));
        assertNotEquals(run.get("levelId"),events.start(p,"tournament",null).get("levelId"));
        assertFalse(Json.write(events.mine(p)).contains("private@example.test"));
        assertFalse(Json.write(events.mine(p)).contains("Private Name"));
    }
    @Test void incompleteForeignAndExpiredRunsCannotScore() {
        var a=player("a");var b=player("b");events.join(a,"Alpha");events.join(b,"Beta");
        var run=events.start(a,"tournament",null);var id=(UUID)run.get("id");
        assertEquals("RUN_NOT_FOUND",assertThrows(ApiException.class,()->events.finish(b,id,ids(run))).code);
        assertEquals("INCOMPLETE_BOARD",assertThrows(ApiException.class,()->events.finish(a,id,List.of(0))).code);
        var duplicates=new ArrayList<>(Collections.nCopies(ids(run).size(),0));
        assertThrows(ApiException.class,()->events.finish(a,id,duplicates));
        db.update("UPDATE event_runs SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),id);
        assertEquals("EVENT_ENDED",assertThrows(ApiException.class,()->events.finish(a,id,ids(run))).code);
        assertEquals(0,((Map<?,?>)events.mine(a).get("me")).get("points"));
    }
    @Test void enrollmentAndDatesAreValidatedAndTiesShareRank() {
        var a=player("a");var b=player("b");
        assertEquals("JOIN_TOURNAMENT_FIRST",assertThrows(ApiException.class,()->events.start(a,"tournament",null)).code);
        assertThrows(ApiException.class,()->events.join(a,"x"));
        assertThrows(ApiException.class,()->events.start(a,"daily",LocalDate.now(ZoneOffset.UTC).plusDays(1).toString()));
        events.join(a,"Alpha");events.join(b,"Beta");
        for(var p:List.of(a,b)){var run=events.start(p,"tournament",null);events.finish(p,(UUID)run.get("id"),ids(run));}
        assertEquals(1L,((Map<?,?>)events.mine(a).get("me")).get("rank"));
        assertEquals(1L,((Map<?,?>)events.mine(b).get("me")).get("rank"));
        events.leave(a);assertNull(events.mine(a).get("me"));
    }
    @Test void publicScheduleIsAccessibleButRunsRequireAuthentication() throws Exception {
        var http=java.net.http.HttpClient.newHttpClient();
        for(var path:List.of("/v1/events","/v1/auth/config")) {
            var response=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
        }
        var denied=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/v1/events/me")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(401,denied.statusCode());
    }
}
