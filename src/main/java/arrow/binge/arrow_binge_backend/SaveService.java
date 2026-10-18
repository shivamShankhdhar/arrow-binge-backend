package arrow.binge.arrow_binge_backend;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class SaveService {
 private final JdbcTemplate db;private final Catalog catalog;
 public SaveService(JdbcTemplate db,Catalog catalog){this.db=db;this.catalog=catalog;}
 public record Level(int id,int stars){}
 public record Attempt(int levelId,UUID runId,long checkpoint,String contentVersion,List<Integer> pulledIds,int lives,int mistakes,List<Integer> wrongIds,boolean usedRevive,boolean closed){}
 public record Snapshot(UUID operationId,long epoch,UUID guestId,List<Level> levels,Set<Integer> unlocked,List<Attempt> attempts,int lastPlayed,boolean sound,boolean haptics){}
 void validate(Snapshot s){
  if(s==null||s.operationId()==null||s.epoch()<0||s.levels()==null||s.levels().size()>1000||s.unlocked()==null||s.unlocked().size()>1000||s.attempts()==null||s.attempts().size()>2000)throw new ApiException(400,"INVALID_SAVE");
  catalog.count(s.lastPlayed());Set<Integer> ids=new HashSet<>();
  for(var l:s.levels()){if(l==null||!ids.add(l.id())||l.stars()<0||l.stars()>3)throw new ApiException(400,"INVALID_STARS");catalog.count(l.id());}
  for(int id:s.unlocked())catalog.count(id);
  Set<String> runs=new HashSet<>();for(var a:s.attempts()){
   if(a==null||a.runId()==null||a.checkpoint()<0||!runs.add(a.levelId()+":"+a.runId())||!Catalog.VERSION.equals(a.contentVersion())||a.lives()<0||a.lives()>5||a.mistakes()<0||a.mistakes()>10000||a.pulledIds()==null||a.wrongIds()==null)throw new ApiException(400,"INVALID_ATTEMPT");
   int count=catalog.count(a.levelId());
   if(a.pulledIds().size()>count||a.wrongIds().size()>count||a.pulledIds().stream().anyMatch(i->i==null||i<0||i>=count)||a.wrongIds().stream().anyMatch(i->i==null||i<0||i>=count))throw new ApiException(400,"INVALID_ARROW");
  }
 }
 @Transactional public Map<String,Object> sync(AuthService.Principal p,Snapshot s){
  validate(s);UUID user=p.userId();
  var owner=db.queryForMap("SELECT epoch,revision FROM users WHERE id=? FOR UPDATE",user);
  long epoch=((Number)owner.get("epoch")).longValue();
  if(epoch!=s.epoch())throw new ApiException(409,"SAVE_RESET_REQUIRED");
  String payload=Crypto.hash(Json.write(s));
  var old=db.queryForList("SELECT payload_hash FROM sync_operations WHERE user_id=? AND operation_id=?",user,s.operationId());
  if(!old.isEmpty()){if(!old.get(0).get("payload_hash").equals(payload))throw new ApiException(409,"OPERATION_REUSED");return read(p);}
  if(s.guestId()!=null){
   int inserted=db.update("INSERT INTO migration_imports(guest_id,user_id,operation_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",s.guestId(),user,s.operationId());
   if(inserted==0)throw new ApiException(409,"GUEST_ALREADY_IMPORTED");
  }
  Set<Integer> unlocked=new HashSet<>(s.unlocked());unlocked.add(1);
  for(var l:s.levels()){
   db.update("INSERT INTO level_progress(user_id,level_id,stars) VALUES(?,?,?) ON CONFLICT(user_id,level_id) DO UPDATE SET stars=GREATEST(level_progress.stars,EXCLUDED.stars)",user,l.id(),l.stars());
   if(l.stars()>0){unlocked.add(l.id());if(catalog.next(l.id())!=null)unlocked.add(catalog.next(l.id()));}
  }
  for(int id:unlocked)db.update("INSERT INTO level_unlocks(user_id,level_id) VALUES(?,?) ON CONFLICT DO NOTHING",user,id);
  for(var a:s.attempts()){
   db.update("INSERT INTO level_attempts(user_id,level_id,device_id,run_id,content_version,checkpoint,state,closed) VALUES(?,?,?,?,?,?,?::jsonb,?) ON CONFLICT(user_id,level_id,device_id,run_id) DO UPDATE SET checkpoint=EXCLUDED.checkpoint,state=EXCLUDED.state,closed=EXCLUDED.closed,updated_at=CURRENT_TIMESTAMP WHERE level_attempts.checkpoint<EXCLUDED.checkpoint AND level_attempts.closed=false",user,a.levelId(),p.deviceId(),a.runId(),a.contentVersion(),a.checkpoint(),Json.write(a),a.closed());
  }
  db.update("INSERT INTO user_preferences(user_id,state) VALUES(?,?::jsonb) ON CONFLICT(user_id) DO UPDATE SET state=EXCLUDED.state",user,Json.write(Map.of("lastPlayed",s.lastPlayed(),"sound",s.sound(),"haptics",s.haptics())));
  long revision=((Number)owner.get("revision")).longValue()+1;
  db.update("UPDATE users SET revision=? WHERE id=?",revision,user);
  db.update("INSERT INTO sync_operations(user_id,operation_id,payload_hash,revision,epoch) VALUES(?,?,?,?,?)",user,s.operationId(),payload,revision,epoch);
  return read(p);
 }
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public Map<String,Object> read(AuthService.Principal p){
  UUID user=p.userId();var result=new HashMap<String,Object>(db.queryForMap("SELECT epoch,revision FROM users WHERE id=?",user));
  result.put("contentVersion",Catalog.VERSION);
  result.put("levels",db.query("SELECT level_id,stars FROM level_progress WHERE user_id=? ORDER BY level_id",(r,n)->new Level(r.getInt(1),r.getInt(2)),user));
  result.put("unlocked",db.queryForList("SELECT level_id FROM level_unlocks WHERE user_id=? ORDER BY level_id",Integer.class,user));
  result.put("attempts",db.query("SELECT state::text,device_id FROM level_attempts WHERE user_id=? AND closed=false ORDER BY updated_at DESC",(r,n)->Map.of("state",Json.MAPPER.convertValue(Json.read(r.getString(1)),Map.class),"deviceId",r.getObject(2).toString()),user));
  var prefs=db.queryForList("SELECT state::text FROM user_preferences WHERE user_id=?",String.class,user);
  result.put("preferences",prefs.isEmpty()?Map.of():Json.MAPPER.convertValue(Json.read(prefs.get(0)),Map.class));return result;
 }
 @Transactional public Map<String,Object> reset(AuthService.Principal p,long expectedEpoch){
  long epoch=db.queryForObject("SELECT epoch FROM users WHERE id=? FOR UPDATE",Long.class,p.userId());
  if(epoch!=expectedEpoch)throw new ApiException(409,"SAVE_RESET_REQUIRED");
  for(String table:List.of("level_progress","level_unlocks","level_attempts","user_preferences"))db.update("DELETE FROM "+table+" WHERE user_id=?",p.userId());
  db.update("UPDATE users SET epoch=epoch+1,revision=revision+1 WHERE id=?",p.userId());return read(p);
 }
}
