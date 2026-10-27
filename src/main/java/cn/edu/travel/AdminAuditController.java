package cn.edu.travel;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {
    private final Database db;private final Auth auth;
    public AdminAuditController(Database db,Auth auth){this.db=db;this.auth=auth;}
    @GetMapping Object list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String action,@RequestParam(defaultValue="") String outcome,@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest req){
        auth.admin(req);
        if(q.length()>100||(!action.isEmpty()&&!AdminAudit.ACTIONS.containsKey(action))||!Set.of("","SUCCESS","FAILED").contains(outcome)||page<1||page>100000||size<1||size>50)throw new ApiError(400,"操作记录筛选条件不正确");
        String start=boundary(from,false),end=boundary(to,true);
        if(!from.isEmpty()&&!to.isEmpty()&&from.compareTo(to)>0)throw new ApiError(400,"开始日期不能晚于结束日期");
        String like="%"+q.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String where=" FROM admin_audit_logs WHERE (actor_name LIKE ? ESCAPE '\\' OR target_label LIKE ? ESCAPE '\\') AND (?='' OR action=?) AND (?='' OR outcome=?) AND (?='' OR created_at>=?) AND (?='' OR created_at<?)";
        var args=new ArrayList<Object>(List.of(like,like,action,action,outcome,outcome,start,start,end,end));
        long total=db.count("SELECT count(*)"+where,args.toArray());args.add(size);args.add((page-1)*size);
        var items=db.list("SELECT *"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray());
        return Map.of("items",items,"total",total,"page",page,"size",size,"actions",AdminAudit.ACTIONS);
    }
    private static String boundary(String value,boolean next){
        if(value.isEmpty())return "";
        try{LocalDate date=LocalDate.parse(value);if(!date.toString().equals(value))throw new IllegalArgumentException();return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format((next?date.plusDays(1):date).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());}catch(Exception e){throw new ApiError(400,"请选择有效的筛选日期");}
    }
}
