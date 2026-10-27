package cn.edu.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** 请求编号由服务端生成；日志仅记录路由模板、状态、耗时及账号编号。 */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+20)
public class RequestTracking extends OncePerRequestFilter {
    private static final String ID="roam.request.id", ROUTE="roam.request.route";
    private static final Logger ACCESS=LoggerFactory.getLogger("roam.requests");
    private final ObjectMapper json;
    public RequestTracking(ObjectMapper json){this.json=json;}
    static String id(HttpServletRequest req){
        Object value=req.getAttribute(ID);
        if(value==null){value=UUID.randomUUID().toString();req.setAttribute(ID,value);}
        return value.toString();
    }
    static String route(HttpServletRequest req){
        Object value=req.getAttribute(ROUTE);
        if(value==null)value=req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // 未匹配的网址可能含用户输入，绝不原样写入日志。
        return value==null?"/unmatched":value.toString().replaceAll("[\\r\\n\\t]", "_");
    }
    private static String actor(HttpServletRequest req){
        try{HttpSession s=req.getSession(false);Object uid=s==null?null:s.getAttribute("uid");return uid instanceof Number?uid.toString():"anonymous";}catch(IllegalStateException e){return "anonymous";}
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        long start=System.nanoTime();String previous=MDC.get("requestId"),initialActor=actor(req);
        MDC.put("requestId",id(req));res.setHeader("X-Request-ID",id(req));
        try{
            chain.doFilter(req,res);
        }catch(Exception error){
            req.setAttribute(ROUTE,route(req));
            ErrorSupport.Failure failure=ErrorSupport.describe(error,500);ErrorSupport.log(req,error,failure.status());
            req.setAttribute("roam.error.code",failure.code());
            if(!res.isCommitted()){
                res.resetBuffer();req.setAttribute(RequestDispatcher.ERROR_EXCEPTION,error);
                req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,failure.status());
                req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI,req.getRequestURI());
                try{req.getRequestDispatcher("/error").forward(req,res);}catch(Exception fallback){
                    if(!res.isCommitted()){res.resetBuffer();res.setStatus(500);res.setCharacterEncoding("UTF-8");res.setContentType("application/json");json.writeValue(res.getWriter(),ErrorSupport.payload(req,new ErrorSupport.Failure(500,"SERVER_ERROR","服务暂时遇到问题，请稍后重试")));}
                }
            }
        }finally{
            int status=res.getStatus();String method=req.getMethod().matches("[A-Z]{1,12}")?req.getMethod():"OTHER";
            if(req.getRequestURI().startsWith("/api/")||status>=400){
                String format="method={} route={} status={} elapsed_ms={} actor={} code={}";
                Object[] args={method,route(req),status,(System.nanoTime()-start)/1_000_000,initialActor.equals("anonymous")?actor(req):initialActor,req.getAttribute("roam.error.code")==null?(status<400?"OK":"HTTP_ERROR"):req.getAttribute("roam.error.code")};
                if(status>=500||Boolean.TRUE.equals(req.getAttribute("roam.error.logged")))ACCESS.error(format,args);else if(status>=400)ACCESS.warn(format,args);else ACCESS.info(format,args);
            }
            if(previous==null)MDC.remove("requestId");else MDC.put("requestId",previous);
        }
    }
}
