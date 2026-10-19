package arrow.binge.arrow_binge_backend;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/leaderboard")
public class LeaderboardController {
    private final LeaderboardService leaderboard;
    public LeaderboardController(LeaderboardService leaderboard) { this.leaderboard = leaderboard; }
    public record Enrollment(String nickname) {}
    @GetMapping public Object read(@RequestAttribute("principal") AuthService.Principal p) { return leaderboard.read(p); }
    @PutMapping("/me") public Object join(@RequestAttribute("principal") AuthService.Principal p,@RequestBody Enrollment body) { return leaderboard.join(p,body.nickname()); }
    @DeleteMapping("/me") public Object leave(@RequestAttribute("principal") AuthService.Principal p) { leaderboard.leave(p);return Map.of("ok",true); }
}
