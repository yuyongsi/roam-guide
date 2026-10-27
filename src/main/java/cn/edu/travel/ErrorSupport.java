package cn.edu.travel;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.ErrorResponse;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

/** 同一个错误在接口、网页及日志之间使用同一个编号。 */
final class ErrorSupport {
    record Failure(int status,String code,String message){}
    static Failure describe(Throwable error,int fallback){
        int status=error instanceof ApiError e?e.status:error instanceof MaxUploadSizeExceededException?413:
            error instanceof org.springframework.beans.TypeMismatchException||error instanceof org.springframework.http.converter.HttpMessageNotReadableException||error instanceof org.springframework.web.multipart.MultipartException?400:
            error instanceof ErrorResponse e?e.getStatusCode().value():fallback;
        String code=switch(status){case 400->"INVALID_REQUEST";case 401->"AUTH_REQUIRED";case 403->"FORBIDDEN";case 404->"NOT_FOUND";case 405->"METHOD_NOT_ALLOWED";case 406->"NOT_ACCEPTABLE";case 409->"CONFLICT";case 413->"UPLOAD_TOO_LARGE";case 415->"UNSUPPORTED_MEDIA_TYPE";case 429->"TOO_MANY_REQUESTS";case 503->"SERVICE_UNAVAILABLE";default->status>=500?"SERVER_ERROR":"INVALID_REQUEST";};
        String message=switch(status){case 400->"提交的参数或内容格式不正确，请检查后重试";case 401->"请先登录后再操作";case 403->"当前账号无权进行这项操作";case 404->"没有找到这条内容或页面";case 405->"当前页面不支持这项操作";case 406->"请求的响应格式暂不支持";case 409->"内容已发生变化，请刷新后重试";case 413->"图片最大为 5 MB，请缩小后重新上传";case 415->"提交的文件或内容格式不支持";case 429->"操作过于频繁，请稍后重试";case 503->"服务暂时不可用，请稍后重试";default->"服务暂时遇到问题，请稍后重试";};
        if(error instanceof ApiError e)message=e.getMessage();
        else if(error instanceof DataAccessException)message="保存未完成，请稍后重试";
        return new Failure(status,code,message);
    }
    static Map<String,Object> payload(HttpServletRequest req,Failure failure){
        req.setAttribute("roam.error.code",failure.code());
        return Map.of("status",failure.status(),"code",failure.code(),"message",failure.message(),"request_id",RequestTracking.id(req));
    }
    static Object respond(HttpServletRequest req,HttpServletResponse res,Throwable error,int fallback){
        Failure failure=describe(error,fallback);log(req,error,failure.status());
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");
        res.setHeader("X-Frame-Options","DENY");res.setHeader("Referrer-Policy","strict-origin-when-cross-origin");
        res.setHeader("X-Request-ID",RequestTracking.id(req));
        var data=payload(req,failure);
        String uri=Objects.toString(req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),req.getRequestURI());
        if(!uri.startsWith("/api/")&&Objects.toString(req.getHeader("Accept"),"").contains("text/html")){
            ModelAndView view=new ModelAndView("error",data);view.setStatus(HttpStatusCode.valueOf(failure.status()));return view;
        }
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setCacheControl("no-store");
        if(error instanceof ErrorResponse e)headers.putAll(e.getHeaders());
        return new ResponseEntity<>(data,headers,HttpStatusCode.valueOf(failure.status()));
    }
    static void log(HttpServletRequest req,Throwable error,int status){
        if(status<500||error==null||Boolean.TRUE.equals(req.getAttribute("roam.error.logged")))return;
        req.setAttribute("roam.error.logged",true);
        // 异常消息可能包含SQL参数、用户名或正文；保留异常类型与代码位置，不记录消息和请求正文。
        StringBuilder trace=new StringBuilder();Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable e=error;e!=null&&seen.size()<5&&seen.add(e);e=e.getCause()){
            trace.append("type=").append(e.getClass().getName()).append('\n');
            StackTraceElement[] frames=e.getStackTrace();for(int i=0;i<Math.min(40,frames.length);i++)trace.append("  at ").append(frames[i]).append('\n');
        }
        LoggerFactory.getLogger(ErrorSupport.class).error("Operation failed status={} route={} diagnostic=\n{}",status,RequestTracking.route(req),trace.toString());
    }
}
