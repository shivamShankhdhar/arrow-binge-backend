package arrow.binge.arrow_binge_backend;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
public class ApiException extends RuntimeException {
 public final int status; public final String code;
 public ApiException(int status,String code) { super(code);this.status=status;this.code=code; }
 @RestControllerAdvice public static class Handler {
  @ExceptionHandler(ApiException.class) public ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(java.util.Map.of("code",e.code)); }
  @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
  public ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(java.util.Map.of("code","INVALID_REQUEST")); }
 }
}
