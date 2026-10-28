package cn.edu.travel;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 只审计白名单后台操作；成功记录与数据库修改共用一个事务。 */
@Aspect @Component
public class AdminAudit {
    private final Database db;
    private final Auth auth;
    private static final Pattern PATH=Pattern.compile("^/api/admin/(spots|guides|cities|foods|users|posts|routes|feedback|uploads)(?:/(\\d+))?(?:/(publication|status|reset-password|moderation))?$");
    static final Map<String,String> ACTIONS=Map.ofEntries(Map.entry("CREATE","新增内容"),Map.entry("EDIT","修改内容"),Map.entry("PUBLICATION","调整发布状态"),Map.entry("USER_STATUS","封禁 / 解封"),Map.entry("RESET_PASSWORD","重置密码"),Map.entry("MODERATION","内容审核"),Map.entry("FEEDBACK","处理反馈"),Map.entry("DELETE_COMMENT","删除评论"),Map.entry("UPLOAD","上传图片"),Map.entry("APPROVE_CONTRIBUTION","批准用户投稿"),Map.entry("RETURN_CONTRIBUTION","退回用户投稿"));
    public AdminAudit(Database db,Auth auth){this.db=db;this.auth=auth;}

    @Around("within(cn.edu.travel.*Controller) && (@annotation(org.springframework.web.bind.annotation.PostMapping) || @annotation(org.springframework.web.bind.annotation.PutMapping) || @annotation(org.springframework.web.bind.annotation.DeleteMapping))")
    public Object record(ProceedingJoinPoint call) throws Throwable {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))return call.proceed();
        HttpServletRequest req=attrs.getRequest();String path=req.getRequestURI(),method=req.getMethod();
        var match=PATH.matcher(path);String resource,id,action;
        if(match.matches()){
            resource=match.group(1);id=match.group(2);String suffix=Objects.toString(match.group(3),"");
            action=resource.equals("uploads")?"UPLOAD":resource.equals("feedback")?"FEEDBACK":suffix.equals("reset-password")?"RESET_PASSWORD":resource.equals("users")?"USER_STATUS":Set.of("posts","routes").contains(resource)?"MODERATION":suffix.equals("publication")?"PUBLICATION":method.equals("POST")?"CREATE":"EDIT";
        }else if(method.equals("DELETE")&&path.matches("/api/(social/)?comments/\\d+")){
            resource=path.contains("/social/")?"social_comments":"comments";id=path.substring(path.lastIndexOf('/')+1);action="DELETE_COMMENT";
        }else return call.proceed();
        var actor=auth.current(req);if(actor==null||!"ADMIN".equals(actor.get("role")))return call.proceed();
        String targetId=id,kind=resource,code=action,requestId=RequestTracking.id(req);
        try{
            return db.transaction(tx->{
                // 先取得 SQLite 写锁，避免读取后升级写事务时产生竞争。
                db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
                String before=label(kind,targetId);Object result;
                try{result=call.proceed();}catch(Throwable e){throw new OperationFailure(e);}
                String resolved=targetId;
                if(resolved==null&&result instanceof Map<?,?> values&&values.get("id")!=null)resolved=String.valueOf(values.get("id"));
                String target=label(kind,resolved);if(target.isBlank())target=before;
                String summary=ACTIONS.get(code)+"完成";
                if(code.equals("USER_STATUS"))summary="BANNED".equals(db.one("SELECT status FROM users WHERE id=?",resolved).get("status"))?"用户已封禁":"用户已解封";
                if(code.equals("PUBLICATION"))summary=((Number)db.one("SELECT published FROM "+kind+" WHERE id=?",resolved).get("published")).intValue()==1?"内容已发布":"内容已下架";
                if(result instanceof Map<?,?> values&&Boolean.FALSE.equals(values.get("created")))summary="目的地已存在，未重复新增";
                insert(actor,code,kind,resolved,target,"SUCCESS",200,summary,method,path,requestId);
                return result;
            });
        }catch(RuntimeException wrapper){
            Throwable error=wrapper instanceof OperationFailure?wrapper.getCause():wrapper;
            int status=error instanceof ApiError e?e.status:500;
            String summary=switch(status){case 400->"参数未通过校验";case 401,403->"操作不被允许";case 404->"目标不存在或不可访问";case 409->"内容版本或状态已变化，请刷新后重试";default->"本次操作未完成";};
            try{insert(actor,code,kind,targetId,label(kind,targetId),"FAILED",status,summary,method,path,requestId);}catch(RuntimeException ignored){ErrorSupport.log(req,ignored,500);}
            throw error;
        }
    }
    private String label(String resource,String id){
        if(resource.equals("uploads"))return "后台图片";
        if(id==null)return "";
        String column=resource.equals("users")?"username":resource.equals("cities")?"name":"title";
        String table=switch(resource){case "spots","guides","cities","foods","users"->resource;case "posts"->"social_posts";case "routes"->"shared_routes";default->null;};
        if(table==null)return (resource.equals("feedback")?"反馈 #":"评论 #")+id;
        var rows=db.list("SELECT "+column+" AS label FROM "+table+" WHERE id=?",id);
        String value=rows.isEmpty()?"#"+id:String.valueOf(rows.get(0).get("label"));return value.substring(0,Math.min(160,value.length()));
    }
    private void insert(Map<String,Object> actor,String action,String resource,String targetId,String target,String outcome,int status,String summary,String method,String path,String requestId){
        db.update("INSERT INTO admin_audit_logs(actor_id,actor_name,action,resource,target_id,target_label,outcome,http_status,summary,request_method,request_path,request_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",actor.get("id"),actor.get("username"),action,resource,targetId,target,outcome,status,summary,method,path,requestId);
    }
    private static class OperationFailure extends RuntimeException{OperationFailure(Throwable cause){super(cause);}}
}
