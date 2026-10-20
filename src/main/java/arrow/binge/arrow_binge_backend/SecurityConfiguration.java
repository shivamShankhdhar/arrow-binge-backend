package arrow.binge.arrow_binge_backend;
import org.springframework.context.annotation.*;import org.springframework.security.config.annotation.web.builders.HttpSecurity;import org.springframework.security.web.SecurityFilterChain;import org.springframework.security.config.http.SessionCreationPolicy;import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;

@Configuration
public class SecurityConfiguration {
 @Bean org.springframework.security.core.userdetails.UserDetailsService noPasswordLogin(){return username->{throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Password login is disabled");};}

 static String clientIp(HttpServletRequest req) {
  String forwarded = req.getHeader("x-forwarded-for");
  if (forwarded != null && !forwarded.isBlank()) {
   String first = forwarded.split(",")[0].trim();
   if (!first.isBlank()) return first;
  }
  String realIp = req.getHeader("x-real-ip");
  if (realIp != null && !realIp.isBlank()) return realIp.trim();
  String remote = req.getRemoteAddr();
  return remote != null ? remote : "unknown";
 }

 @Bean SecurityFilterChain chain(HttpSecurity http,AuthService auth,org.springframework.jdbc.core.JdbcTemplate db)throws Exception{
  return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.anyRequest().permitAll()).addFilterBefore(new OncePerRequestFilter(){
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain next)throws ServletException,IOException{
     try{
      res.setHeader("Access-Control-Allow-Origin", "*");
      res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
      res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept, X-Requested-With");

      if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
       res.setHeader("Access-Control-Max-Age", "3600");
       res.setStatus(HttpServletResponse.SC_OK);
       return;
      }

      if(req.getContentLengthLong()>4194304)throw new ApiException(413,"REQUEST_TOO_LARGE");
      String path=req.getRequestURI();
      if(path.startsWith("/v1/")){
       boolean publicAuth=path.equals("/v1/auth/challenge")||path.equals("/v1/auth/google")||path.equals("/v1/auth/refresh")||("GET".equals(req.getMethod())&&(path.equals("/v1/events")||path.equals("/v1/events/tournament/standings")||path.equals("/v1/auth/config")));
       if(publicAuth){String bucket=Crypto.hash(clientIp(req));long window=System.currentTimeMillis()/60000;
        db.update("INSERT INTO request_limits(bucket,window_start,requests) VALUES(?,?,1) ON CONFLICT(bucket) DO UPDATE SET requests=CASE WHEN request_limits.window_start=EXCLUDED.window_start THEN request_limits.requests+1 ELSE 1 END,window_start=EXCLUDED.window_start",bucket,window);
        if(db.queryForObject("SELECT requests FROM request_limits WHERE bucket=?",Integer.class,bucket)>30)throw new ApiException(429,"RATE_LIMITED");
       }else if(!path.equals("/v1/rewards/admob/callback")){
        String header=req.getHeader("Authorization");if(header==null||!header.startsWith("Bearer "))throw new ApiException(401,"SIGN_IN_REQUIRED");
        var principal=auth.authenticate(header.substring(7));
        req.setAttribute("principal",principal);
        String bucket="user:"+principal.userId();long window=System.currentTimeMillis()/60000;
        db.update("INSERT INTO request_limits(bucket,window_start,requests) VALUES(?,?,1) ON CONFLICT(bucket) DO UPDATE SET requests=CASE WHEN request_limits.window_start=EXCLUDED.window_start THEN request_limits.requests+1 ELSE 1 END,window_start=EXCLUDED.window_start",bucket,window);
        if(db.queryForObject("SELECT requests FROM request_limits WHERE bucket=?",Integer.class,bucket)>120)throw new ApiException(429,"RATE_LIMITED");
       }
      }
      next.doFilter(new BoundedRequest(req),res);
     }catch(ApiException e){res.setStatus(e.status);res.setContentType("application/json");res.getWriter().write(Json.write(java.util.Map.of("code",e.code)));}
    }
   },UsernamePasswordAuthenticationFilter.class).build();
 }
}

