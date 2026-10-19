package arrow.binge.arrow_binge_backend;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseUrlParserTest {
    @Test
    void parsesNeonConnectionUrl() {
        String input = "postgresql://neondb_owner:npg_BFczKy7Yl9NJ@ep-proud-paper-b53xvjx0-pooler.c-7.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require";
        Map<String, String> parsed = DatabaseUrlParser.parse(input);

        assertEquals("neondb_owner", parsed.get("username"));
        assertEquals("npg_BFczKy7Yl9NJ", parsed.get("password"));
        assertEquals("jdbc:postgresql://ep-proud-paper-b53xvjx0-pooler.c-7.us-east-2.aws.neon.tech:5432/neondb?sslmode=require&channel_binding=require", parsed.get("url"));
    }

    @Test
    void preservesExistingJdbcUrl() {
        String input = "jdbc:postgresql://localhost:5432/arrow_binge";
        Map<String, String> parsed = DatabaseUrlParser.parse(input);

        assertNull(parsed.get("username"));
        assertNull(parsed.get("password"));
        assertEquals(input, parsed.get("url"));
    }

    @Test
    void handlesPostgresProtocol() {
        String input = "postgres://user:pass@db.example.com:5433/mydb";
        Map<String, String> parsed = DatabaseUrlParser.parse(input);

        assertEquals("user", parsed.get("username"));
        assertEquals("pass", parsed.get("password"));
        assertEquals("jdbc:postgresql://db.example.com:5433/mydb", parsed.get("url"));
    }
}
