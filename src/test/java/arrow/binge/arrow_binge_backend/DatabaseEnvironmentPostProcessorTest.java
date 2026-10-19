package arrow.binge.arrow_binge_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseEnvironmentPostProcessorTest {
    @Test
    void postProcessorOverridesDatasourceProperties() {
        StandardEnvironment env = new StandardEnvironment();
        String neonUrl = "postgresql://neondb_owner:npg_BFczKy7Yl9NJ@ep-proud-paper-b53xvjx0-pooler.c-7.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require";
        env.getPropertySources().addFirst(new MapPropertySource("testEnv", Map.of("DATABASE_URL", neonUrl)));

        DatabaseEnvironmentPostProcessor processor = new DatabaseEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("jdbc:postgresql://ep-proud-paper-b53xvjx0-pooler.c-7.us-east-2.aws.neon.tech:5432/neondb?sslmode=require&channel_binding=require", env.getProperty("spring.datasource.url"));
        assertEquals("neondb_owner", env.getProperty("spring.datasource.username"));
        assertEquals("npg_BFczKy7Yl9NJ", env.getProperty("spring.datasource.password"));
    }
}
