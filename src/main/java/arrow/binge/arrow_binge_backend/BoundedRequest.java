package arrow.binge.arrow_binge_backend;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
/** Enforce the JSON limit even for chunked requests. */
final class BoundedRequest extends HttpServletRequestWrapper {
 private final byte[] body;
 BoundedRequest(HttpServletRequest request) throws IOException {
  super(request);body=request.getInputStream().readNBytes(4194305);
  if(body.length>4194304)throw new ApiException(413,"REQUEST_TOO_LARGE");
 }
 @Override public ServletInputStream getInputStream(){
  var input=new ByteArrayInputStream(body);
  return new ServletInputStream(){
   public int read(){return input.read();}
   public boolean isFinished(){return input.available()==0;}
   public boolean isReady(){return true;}
   public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
  };
 }
 @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
}
