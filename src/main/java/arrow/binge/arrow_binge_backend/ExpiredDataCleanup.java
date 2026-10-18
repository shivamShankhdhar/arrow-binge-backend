package arrow.binge.arrow_binge_backend;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
@Component
@EnableScheduling
public class ExpiredDataCleanup {
 private final JdbcTemplate db;
 public ExpiredDataCleanup(JdbcTemplate db){this.db=db;}
 @Scheduled(fixedDelay=3600000,initialDelay=3600000)
 public void clean(){
  db.update("DELETE FROM auth_challenges WHERE expires_at<CURRENT_TIMESTAMP");
  db.update("DELETE FROM request_limits WHERE window_start<?",System.currentTimeMillis()/60000-60);
  db.update("DELETE FROM device_sessions WHERE absolute_expires_at<CURRENT_TIMESTAMP OR refresh_expires_at<CURRENT_TIMESTAMP");
 }
}
