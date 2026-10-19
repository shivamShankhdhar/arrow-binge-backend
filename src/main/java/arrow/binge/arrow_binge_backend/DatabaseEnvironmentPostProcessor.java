package arrow.binge.arrow_binge_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

public class DatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String dbUrl = environment.getProperty("DATABASE_URL");
        if (dbUrl == null || dbUrl.isBlank()) {
            dbUrl = environment.getProperty("DB_URL");
        }
        if (dbUrl == null || dbUrl.isBlank()) {
            dbUrl = environment.getProperty("spring.datasource.url");
        }
        if (dbUrl == null || dbUrl.isBlank()) {
            return;
        }

        Map<String, String> parsed = DatabaseUrlParser.parse(dbUrl);
        if (parsed.isEmpty()) {
            return;
        }

        Map<String, Object> overrides = new HashMap<>();
        if (parsed.containsKey("url")) {
            overrides.put("spring.datasource.url", parsed.get("url"));
        }
        if (parsed.containsKey("username") && (environment.getProperty("DB_USERNAME") == null || "arrow_binge".equals(environment.getProperty("DB_USERNAME")))) {
            overrides.put("spring.datasource.username", parsed.get("username"));
        }
        if (parsed.containsKey("password") && (environment.getProperty("DB_PASSWORD") == null || environment.getProperty("DB_PASSWORD").isBlank())) {
            overrides.put("spring.datasource.password", parsed.get("password"));
        }

        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("databaseUrlOverrides", overrides));
        }
    }
}
