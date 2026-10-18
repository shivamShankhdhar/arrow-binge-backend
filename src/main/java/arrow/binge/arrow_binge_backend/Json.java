package arrow.binge.arrow_binge_backend;
import com.fasterxml.jackson.databind.*;
public final class Json {
 public static final ObjectMapper MAPPER = new ObjectMapper();
 public static String write(Object value) { try { return MAPPER.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid JSON"); } }
 public static JsonNode read(String value) { try { return MAPPER.readTree(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid JSON"); } }
}
