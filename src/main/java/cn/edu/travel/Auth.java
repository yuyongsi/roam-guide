package cn.edu.travel;

import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Service;

@Service
class Auth {
    final Database db;
    Auth(Database db){this.db=db;}
    String csrf(HttpServletRequest req) {
        HttpSession session=req.getSession(true);
        if(session.getAttribute("csrf")==null)session.setAttribute("csrf",Passwords.token());
        return (String)session.getAttribute("csrf");
    }
    Map<String,Object> current(HttpServletRequest req) {
        HttpSession session=req.getSession(false);
        if(session==null || session.getAttribute("uid")==null)return null;
        var rows=db.list("SELECT * FROM users WHERE id=?",session.getAttribute("uid"));
        if(rows.isEmpty() || !"ACTIVE".equals(rows.get(0).get("status")) ||
                !String.valueOf(rows.get(0).get("auth_version")).equals(String.valueOf(session.getAttribute("version")))) {
            session.invalidate(); return null;
        }
        return rows.get(0);
    }
    Map<String,Object> require(HttpServletRequest req) {
        var user=current(req);
        if(user==null)throw new ApiError(401,"请先登录后再操作");
        return user;
    }
    Map<String,Object> admin(HttpServletRequest req) {
        var user=require(req);
        if(!"ADMIN".equals(user.get("role")))throw new ApiError(403,"只有管理员可以进行这项操作");
        return user;
    }
    Map<String,Object> publicUser(Map<String,Object> user) {
        if(user==null)return null;
        return Map.of("id",user.get("id"),"username",user.get("username"),"role",user.get("role"),
                "must_change_password",((Number)user.get("must_change_password")).intValue()==1);
    }
    void signIn(HttpServletRequest req,Map<String,Object> user) {
        req.getSession(true); req.changeSessionId();
        req.getSession().setAttribute("uid",user.get("id"));
        req.getSession().setAttribute("version",user.get("auth_version"));
        req.getSession().setAttribute("csrf",Passwords.token());
    }
}
