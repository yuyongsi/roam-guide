package cn.edu.travel;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/** 统一接住容器转来的404/500，并保留真正的HTTP状态。 */
@Controller
public class SiteErrorController implements ErrorController {
    @RequestMapping("/error") Object error(HttpServletRequest req,HttpServletResponse res){
        Object raw=req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status=raw instanceof Number n?n.intValue():404;
        Object failure=req.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        return ErrorSupport.respond(req,res,failure instanceof Throwable t?t:null,status);
    }
}
