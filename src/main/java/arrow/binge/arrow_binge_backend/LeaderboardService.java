package arrow.binge.arrow_binge_backend;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaderboardService {
    private final JdbcTemplate db;
    public LeaderboardService(JdbcTemplate db) { this.db = db; }

    // Called inside the save/reset transaction; retries cannot award points twice.
    public void updateScore(UUID user) {
        db.update("""
            UPDATE leaderboard_profiles SET
              stars=(SELECT COALESCE(SUM(stars),0) FROM level_progress WHERE user_id=?),
              completed=(SELECT COUNT(*) FROM level_progress WHERE user_id=? AND stars>0)
            WHERE user_id=?
            """, user, user, user);
    }

    @Transactional
    public Map<String,Object> join(AuthService.Principal p, String nickname) {
        if (nickname == null) throw new ApiException(400,"NICKNAME_REQUIRED");
        nickname = nickname.strip();
        if (!nickname.matches("[\\p{L}\\p{N} _-]{2,24}"))
            throw new ApiException(400,"INVALID_NICKNAME");
        // Use the same lock order as save/reset so enrollment never races a completion.
        db.queryForObject("SELECT revision FROM users WHERE id=? FOR UPDATE", Long.class, p.userId());
        db.update("INSERT INTO leaderboard_profiles(user_id,nickname) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET nickname=EXCLUDED.nickname",p.userId(),nickname);
        updateScore(p.userId());
        return read(p);
    }

    @Transactional
    public void leave(AuthService.Principal p) {
        db.queryForObject("SELECT revision FROM users WHERE id=? FOR UPDATE", Long.class, p.userId());
        db.update("DELETE FROM leaderboard_profiles WHERE user_id=?",p.userId());
    }

    private Map<String,Object> entry(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return Map.of("id",r.getObject("user_id").toString(), "nickname",r.getString("nickname"),
            "stars",r.getInt("stars"), "completed",r.getInt("completed"), "rank",r.getLong("position"));
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> read(AuthService.Principal p) {
        // Read only the top 50 using the score index; do not sort the entire player population.
        var leaders = db.query("""
            SELECT *, RANK() OVER(ORDER BY stars DESC,completed DESC) AS position FROM
              (SELECT * FROM leaderboard_profiles ORDER BY stars DESC,completed DESC,user_id LIMIT 50) top_players
            ORDER BY stars DESC,completed DESC,user_id
            """,this::entry);
        var mine = db.query("""
            SELECT mine.*, 1+(SELECT COUNT(*) FROM leaderboard_profiles other
              WHERE (other.stars,other.completed)>(mine.stars,mine.completed)) AS position
            FROM leaderboard_profiles mine WHERE user_id=?
            """,this::entry,p.userId());
        var result = new HashMap<String,Object>();
        result.put("entries",leaders);
        result.put("me",mine.isEmpty()?null:mine.get(0));
        result.put("participants",db.queryForObject("SELECT COUNT(*) FROM leaderboard_profiles",Long.class));
        result.put("updatedAt",java.time.Instant.now().toString());
        return result;
    }
}
