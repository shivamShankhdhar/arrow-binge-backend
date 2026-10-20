package arrow.binge.arrow_binge_backend;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
    private final JdbcTemplate db;
    private final Catalog catalog;
    public EventService(JdbcTemplate db, Catalog catalog) { this.db=db; this.catalog=catalog; }
    public Map<String,Object> schedule() {
        var now=Instant.now(); var day=EventSchedule.today(now); var week=EventSchedule.week(day);
        ensureTournamentContenders(week);
        return Map.of("serverTime",now.toString(),"today",day.toString(),"dailyLevelId",EventSchedule.dailyLevel(day),
            "resetsAt",day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
            "week",week.toString(),"endsAt",EventSchedule.weekEnd(week).toString());
    }
    private void lock(AuthService.Principal p) { db.queryForObject("SELECT revision FROM users WHERE id=? FOR UPDATE",Long.class,p.userId()); }
    private LocalDate date(String value) {
        try { return LocalDate.parse(value); } catch(Exception e) { throw new ApiException(400,"INVALID_EVENT_DATE"); }
    }
    private String nickname(String value) {
        if(value==null || !value.strip().matches("[\\p{L}\\p{N} _-]{2,24}")) throw new ApiException(400,"INVALID_NICKNAME");
        return value.strip();
    }
    @Transactional public Map<String,Object> join(AuthService.Principal p,String name) {
        name=nickname(name); lock(p); var week=EventSchedule.week(EventSchedule.today(Instant.now()));
        ensureTournamentContenders(week);
        db.update("INSERT INTO tournament_players(user_id,week,nickname) VALUES(?,?,?) ON CONFLICT(user_id,week) DO UPDATE SET nickname=EXCLUDED.nickname",p.userId(),week,name);
        return mine(p);
    }
    @Transactional public void leave(AuthService.Principal p) {
        lock(p); var week=EventSchedule.week(EventSchedule.today(Instant.now()));
        db.update("DELETE FROM tournament_completions WHERE user_id=? AND week=?",p.userId(),week);
        db.update("DELETE FROM tournament_players WHERE user_id=? AND week=?",p.userId(),week);
        // Invalidate unfinished tickets on withdrawal.
        db.update("DELETE FROM event_runs WHERE user_id=? AND mode='tournament' AND event_key=?",p.userId(),week.toString());
    }
    @Transactional public Map<String,Object> start(AuthService.Principal p,String mode,String requestedDay) {
        lock(p); var now=Instant.now();var today=EventSchedule.today(now);
        String key; int level; Instant expiry;
        if("daily".equals(mode)) {
            var day=date(requestedDay);
            if(day.isAfter(today)||day.isBefore(today.minusDays(62))) throw new ApiException(400,"DAY_NOT_AVAILABLE");
            key=day.toString();level=EventSchedule.dailyLevel(day);expiry=now.plusSeconds(86400);
        } else if("tournament".equals(mode)) {
            var week=EventSchedule.week(today);key=week.toString();expiry=EventSchedule.weekEnd(week);
            ensureTournamentContenders(week);
            if(db.queryForObject("SELECT COUNT(*) FROM tournament_players WHERE user_id=? AND week=?",Integer.class,p.userId(),week)==0) throw new ApiException(409,"JOIN_TOURNAMENT_FIRST");
            var completed=new HashSet<>(db.queryForList("SELECT level_id FROM tournament_completions WHERE user_id=? AND week=?",Integer.class,p.userId(),week));
            level=EventSchedule.tournamentLevels(week).stream().filter(id->!completed.contains(id)).findFirst().orElseThrow(()->new ApiException(409,"ALL_BOARDS_COMPLETED"));
        } else throw new ApiException(400,"INVALID_EVENT_MODE");
        // Retain one live ticket per board so reconnect/repeated Play cannot farm scores.
        var existing=db.queryForList("SELECT id FROM event_runs WHERE user_id=? AND mode=? AND event_key=? AND level_id=? AND finished_at IS NULL AND expires_at>? ORDER BY started_at DESC LIMIT 1",p.userId(),mode,key,level,Timestamp.from(now));
        UUID id=existing.isEmpty()?UUID.randomUUID():(UUID)existing.get(0).get("id");
        if(existing.isEmpty()) db.update("INSERT INTO event_runs(id,user_id,mode,event_key,level_id,expires_at) VALUES(?,?,?,?,?,?)",id,p.userId(),mode,key,level,Timestamp.from(expiry));
        return Map.of("id",id,"mode",mode,"key",key,"levelId",level,"expiresAt",expiry.toString());
    }
    @Transactional public Map<String,Object> finish(AuthService.Principal p,UUID id,List<Integer> pulled) {
        lock(p);
        var runs=db.queryForList("SELECT * FROM event_runs WHERE id=? AND user_id=? FOR UPDATE",id,p.userId());
        if(runs.isEmpty()) throw new ApiException(404,"RUN_NOT_FOUND");
        var r=runs.get(0);
        if(r.get("finished_at")!=null) return Map.of("accepted",true,"points",r.get("points"));
        var now=Instant.now();
        if(!((Timestamp)r.get("expires_at")).toInstant().isAfter(now)) throw new ApiException(409,"EVENT_ENDED");
        int level=((Number)r.get("level_id")).intValue();int count=catalog.count(level);
        if(pulled==null||pulled.size()!=count||new HashSet<>(pulled).size()!=count||pulled.stream().anyMatch(n->n==null||n<0||n>=count)) throw new ApiException(400,"INCOMPLETE_BOARD");
        int points=0;
        if("daily".equals(r.get("mode"))) {
            db.update("INSERT INTO daily_completions(user_id,day,level_id) VALUES(?,?,?) ON CONFLICT(user_id,day) DO NOTHING",p.userId(),date((String)r.get("event_key")),level);
        } else {
            var week=date((String)r.get("event_key"));
            if(!EventSchedule.week(EventSchedule.today(now)).equals(week)) throw new ApiException(409,"EVENT_ENDED");
            if(db.queryForObject("SELECT COUNT(*) FROM tournament_players WHERE user_id=? AND week=?",Integer.class,p.userId(),week)==0) throw new ApiException(409,"JOIN_TOURNAMENT_FIRST");
            int added=db.update("INSERT INTO tournament_completions(user_id,week,level_id,points) VALUES(?,?,?,?) ON CONFLICT(user_id,week,level_id) DO NOTHING",p.userId(),week,level,count);
            points=added==0?0:count;
            db.update("UPDATE tournament_players SET points=points+?,completed=completed+? WHERE user_id=? AND week=?",points,added,p.userId(),week);
        }
        db.update("UPDATE event_runs SET finished_at=?,points=? WHERE id=?",Timestamp.from(now),points,id);
        return Map.of("accepted",true,"points",points);
    }
    public Map<String,Object> export(AuthService.Principal p) {
        return Map.of("dailyCompletions",db.queryForList("SELECT day,level_id,completed_at FROM daily_completions WHERE user_id=? ORDER BY day",p.userId()),
            "tournaments",db.queryForList("SELECT week,nickname,points,completed FROM tournament_players WHERE user_id=? ORDER BY week",p.userId()),
            "tournamentBoards",db.queryForList("SELECT week,level_id,points FROM tournament_completions WHERE user_id=? ORDER BY week,level_id",p.userId()));
    }
    private Map<String,Object> entry(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return Map.of("id",r.getObject("user_id").toString(),"nickname",r.getString("nickname"),"points",r.getInt("points"),"completed",r.getInt("completed"),"rank",r.getLong("position"));
    }

    @Transactional
    public void ensureTournamentContenders(LocalDate week) {
        Long count = db.queryForObject("SELECT COUNT(*) FROM tournament_players WHERE week=?", Long.class, week);
        if (count != null && count >= 10) return;
        String[][] contenders = {
            {"SpeedyFalcon", "210", "15"},
            {"CosmicOwl", "195", "14"},
            {"ZenithPlayer", "182", "13"},
            {"NeonArrow", "170", "12"},
            {"MysticFox", "156", "11"},
            {"TurboBadger", "144", "10"},
            {"QuantumLeap", "130", "9"},
            {"SilverWolf", "118", "8"},
            {"ApexSeeker", "105", "7"},
            {"PixelHunter", "96", "7"},
            {"ShadowLynx", "88", "6"},
            {"BlazeRunner", "78", "5"},
            {"StormChaser", "70", "5"},
            {"EchoViper", "62", "4"},
            {"PrismHawk", "55", "4"},
            {"VortexBear", "48", "3"},
            {"RadiantOtter", "42", "3"},
            {"NovaTiger", "36", "2"},
            {"AlphaStag", "30", "2"},
            {"SwiftPanda", "25", "2"},
            {"AeroFalcon", "20", "1"},
            {"OrbitRaven", "16", "1"},
            {"AstroKoala", "12", "1"},
            {"PulseCheetah", "8", "1"},
            {"CyberMantis", "6", "1"},
            {"SolarEagle", "4", "0"},
            {"HyperGecko", "2", "0"},
            {"LunarBison", "0", "0"},
            {"DynamicLynx", "0", "0"},
            {"CrimsonFox", "0", "0"}
        };
        for (int i = 0; i < contenders.length; i++) {
            UUID botId = UUID.nameUUIDFromBytes(("tournament_contender_" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String name = contenders[i][0];
            int pts = Integer.parseInt(contenders[i][1]);
            int done = Integer.parseInt(contenders[i][2]);
            db.update("INSERT INTO users(id, google_subject, display_name) VALUES(?, ?, ?) ON CONFLICT(id) DO NOTHING", botId, "contender:" + i, name);
            db.update("INSERT INTO tournament_players(user_id, week, nickname, points, completed) VALUES(?, ?, ?, ?, ?) ON CONFLICT(user_id, week) DO NOTHING", botId, week, name, pts, done);
        }
    }

    @Transactional
    public Map<String,Object> standings() {
        var week=EventSchedule.week(EventSchedule.today(Instant.now()));
        ensureTournamentContenders(week);
        var result=new HashMap<String,Object>();
        result.put("entries",db.query("SELECT *,RANK() OVER(ORDER BY points DESC,completed DESC) AS position FROM (SELECT * FROM tournament_players WHERE week=? ORDER BY points DESC,completed DESC,user_id LIMIT 50) leaders ORDER BY points DESC,completed DESC,user_id",this::entry,week));
        result.put("participants",db.queryForObject("SELECT COUNT(*) FROM tournament_players WHERE week=?",Long.class,week));
        result.put("week",week.toString());
        result.put("endsAt",EventSchedule.weekEnd(week).toString());
        return result;
    }

    @Transactional
    public Map<String,Object> mine(AuthService.Principal p) {
        var week=EventSchedule.week(EventSchedule.today(Instant.now()));
        ensureTournamentContenders(week);
        var result=new HashMap<String,Object>();
        result.put("dailyDays",db.queryForList("SELECT CAST(day AS VARCHAR) FROM daily_completions WHERE user_id=? ORDER BY day DESC LIMIT 100",String.class,p.userId()));
        result.put("entries",db.query("SELECT *,RANK() OVER(ORDER BY points DESC,completed DESC) AS position FROM (SELECT * FROM tournament_players WHERE week=? ORDER BY points DESC,completed DESC,user_id LIMIT 50) leaders ORDER BY points DESC,completed DESC,user_id",this::entry,week));
        var mine=db.query("SELECT mine.*,1+(SELECT COUNT(*) FROM tournament_players other WHERE other.week=mine.week AND (other.points,other.completed)>(mine.points,mine.completed)) AS position FROM tournament_players mine WHERE user_id=? AND week=?",this::entry,p.userId(),week);
        result.put("me",mine.isEmpty()?null:mine.get(0));
        result.put("participants",db.queryForObject("SELECT COUNT(*) FROM tournament_players WHERE week=?",Long.class,week));
        result.put("week",week.toString());
        return result;
    }
}
