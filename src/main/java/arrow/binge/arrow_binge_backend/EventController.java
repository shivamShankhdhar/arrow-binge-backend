package arrow.binge.arrow_binge_backend;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/v1/events")
public class EventController {
    private final EventService events;
    public EventController(EventService events) { this.events=events; }
    public record Enrollment(String nickname) {}
    public record Start(String mode,String day) {}
    public record Finish(List<Integer> pulledIds) {}
    @GetMapping public Object schedule() { return events.schedule(); }
    @GetMapping("/tournament/standings") public Object standings() { return events.standings(); }
    @GetMapping("/me") public Object mine(@RequestAttribute("principal") AuthService.Principal p) { return events.mine(p); }
    @PutMapping("/tournament") public Object join(@RequestAttribute("principal") AuthService.Principal p,@RequestBody Enrollment body) { return events.join(p,body.nickname()); }
    @DeleteMapping("/tournament") public Object leave(@RequestAttribute("principal") AuthService.Principal p) { events.leave(p);return Map.of("ok",true); }
    @PostMapping("/runs") public Object start(@RequestAttribute("principal") AuthService.Principal p,@RequestBody Start body) { return events.start(p,body.mode(),body.day()); }
    @PostMapping("/runs/{id}/finish") public Object finish(@RequestAttribute("principal") AuthService.Principal p,@PathVariable UUID id,@RequestBody Finish body) { return events.finish(p,id,body.pulledIds()); }
}
