package arrow.binge.arrow_binge_backend;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {
    private final ExpiredDataCleanup cleanup;
    private final String cronSecret;

    public RootController(ExpiredDataCleanup cleanup, @Value("${app.cron-secret:}") String cronSecret) {
        this.cleanup = cleanup;
        this.cronSecret = cronSecret;
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
            "status", "UP",
            "service", "arrow-binge-backend",
            "version", Catalog.VERSION,
            "timestamp", java.time.Instant.now().toString()
        );
    }


    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/internal/cleanup")
    public Map<String, Object> runCleanup(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (cronSecret != null && !cronSecret.isBlank()) {
            String expected = "Bearer " + cronSecret;
            if (authHeader == null || !expected.equals(authHeader)) {
                throw new ApiException(401, "UNAUTHORIZED");
            }
        }
        cleanup.clean();
        return Map.of("ok", true, "message", "Cleanup executed");
    }
}
