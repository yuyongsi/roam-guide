package cn.edu.travel;

import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;

@Configuration
class WebConfiguration implements WebMvcConfigurer {
    final Auth auth;
    WebConfiguration(Auth auth){this.auth=auth;}
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations(TravelApplication.DATA.resolve("uploads").toUri().toString());
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor(){
            @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
                res.setHeader("X-Content-Type-Options","nosniff");
                res.setHeader("Referrer-Policy","strict-origin-when-cross-origin");
                res.setHeader("X-Frame-Options","DENY");
                res.setHeader("Cache-Control","no-store");
                var user=auth.current(req);
                if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) {
                    String origin=req.getHeader("Origin");
                    String expected=req.getScheme()+"://"+req.getHeader("Host");
                    if(origin!=null && !origin.equals(expected))throw new ApiError(403,"请求来源不正确，请刷新页面");
                    if(!auth.csrf(req).equals(req.getHeader("X-CSRF-Token")))throw new ApiError(403,"页面状态已更新，请刷新后重试");
                    if(user!=null && ((Number)user.get("must_change_password")).intValue()==1 &&
                        !Set.of("/api/user/password","/api/auth/logout").contains(req.getRequestURI()))
                        throw new ApiError(409,"请先修改管理员提供的临时密码");
                }
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}

@Controller
class PageController {
    @GetMapping("/") String home(){return "home";}
    @GetMapping({"/destinations","/destination/{slug}"}) String destinations(){return "destinations";}
    @GetMapping("/spot/{id}") String spot(){return "spot";}
    @GetMapping("/guides") String guides(){return "guides";}
    @GetMapping("/guide/{id}") String guide(){return "guide";}
    @GetMapping("/search") String search(){return "search";}
    @GetMapping("/feedback") String feedback(){return "me";}
    @GetMapping({"/food","/food/{id}"}) String food(){return "me";}
    @GetMapping({"/community","/community/post/{id}"}) String community(){return "me";}
    @GetMapping("/me") String me(){return "me";}
    @GetMapping({"/trips","/trip/{id}"}) String trips(){return "me";}
    @GetMapping({"/routes","/routes/{id}","/recommend","/planner"}) String experience(){return "me";}
    @GetMapping("/admin") String admin(){return "admin";}
}

class ApiError extends RuntimeException {
    final int status;
    ApiError(int status,String message){super(message);this.status=status;}
}

@ControllerAdvice
class ApiErrors {
    @ExceptionHandler(Exception.class) Object application(Exception error,HttpServletRequest req,HttpServletResponse res) {
        return ErrorSupport.respond(req,res,error,500);
    }
}
