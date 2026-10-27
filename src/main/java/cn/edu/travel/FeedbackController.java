package cn.edu.travel;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static cn.edu.travel.ApiController.*;

/** 用户反馈仅向本人和管理员开放，管理回复不会改变原始反馈。 */
@RestController
@RequestMapping("/api")
public class FeedbackController {
    final Database db; final Auth auth;
    public FeedbackController(Database db,Auth auth){this.db=db;this.auth=auth;}
    @GetMapping("/me/feedback") Object mine(HttpServletRequest req) {
        var user=auth.require(req);
        return db.list("SELECT id,category,body,page_path,status,admin_reply,created_at,updated_at FROM feedback WHERE user_id=? ORDER BY id DESC",user.get("id"));
    }
    @PostMapping("/me/feedback") Object submit(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        var user=auth.require(req);
        String category=text(body,"category",1,20),content=raw(body,"body").trim(),path=text(body,"page_path",0,200);
        if(content.isEmpty()||content.length()>1000)throw new ApiError(400,"反馈内容请填写 1～1000 个字符");
        if(!Set.of("内容纠错","功能建议","使用问题").contains(category))throw new ApiError(400,"请选择有效的反馈类型");
        if(!path.isEmpty()&&!path.matches("^/(?:spot/[1-9][0-9]*|guide/[1-9][0-9]*|destination/[a-z0-9-]+)$"))throw new ApiError(400,"相关页面只支持本站景点、攻略或目的地路径，例如 /spot/4");
        long id=db.transaction(status->{
            if(db.count("SELECT count(*) FROM feedback WHERE user_id=?",user.get("id"))>=100)throw new ApiError(400,"每个账号最多保留 100 条反馈");
            return db.insert("INSERT INTO feedback(user_id,category,body,page_path) VALUES(?,?,?,?)",user.get("id"),category,content,path);
        });
        return Map.of("id",id,"message","反馈已提交，可在下方查看处理进度");
    }
    @GetMapping("/admin/feedback") Object all(@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,HttpServletRequest req) {
        auth.admin(req);
        if(!Set.of("","PENDING","RESOLVED").contains(status))throw new ApiError(400,"请选择有效的处理状态");
        q=q.trim();if(q.length()>100)throw new ApiError(400,"搜索词请控制在 100 字以内");
        page=Math.max(1,Math.min(100000,page));String like="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String where=" FROM feedback f JOIN users u ON u.id=f.user_id WHERE (?='' OR f.status=?) AND (f.body LIKE ? ESCAPE '\\' OR u.username LIKE ? ESCAPE '\\' OR f.category LIKE ? ESCAPE '\\')";
        Object[] args={status,status,like,like,like};long total=db.count("SELECT count(*)"+where,args);
        List<Object> values=new ArrayList<>(Arrays.asList(args));values.add(20);values.add((page-1)*20);
        var rows=db.list("SELECT f.*,u.username"+where+" ORDER BY f.id DESC LIMIT ? OFFSET ?",values.toArray());
        return Map.of("items",rows,"total",total,"page",page,"size",20,"pending",db.count("SELECT count(*) FROM feedback WHERE status='PENDING'"),"resolved",db.count("SELECT count(*) FROM feedback WHERE status='RESOLVED'"));
    }
    @PutMapping("/admin/feedback/{id}") Object reply(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        auth.admin(req);db.one("SELECT id FROM feedback WHERE id=?",id);
        String status=text(body,"status",1,20),reply=text(body,"admin_reply",0,1000);
        if(!Set.of("PENDING","RESOLVED").contains(status))throw new ApiError(400,"请选择有效的处理状态");
        if(status.equals("RESOLVED")&&reply.isBlank())throw new ApiError(400,"标记已处理前，请填写回复说明");
        db.update("UPDATE feedback SET status=?,admin_reply=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",status,reply,id);
        return Map.of("message",status.equals("RESOLVED")?"反馈已回复并标记已处理":"反馈已恢复待处理");
    }
}
