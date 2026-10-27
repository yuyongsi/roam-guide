package cn.edu.travel;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/lists")
public class AdminListsController {
    private final Database db; private final Auth auth;
    public AdminListsController(Database db, Auth auth){this.db=db;this.auth=auth;}
    record Spec(String select, String from, String filter, String order) {}
    @GetMapping("/{entity}") Object list(@PathVariable String entity, @RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, HttpServletRequest req){
        auth.admin(req); q=q.trim(); if(q.length()>100 || !List.of(10,20,50).contains(size))throw new ApiError(400,"分页或搜索条件不正确");
        Spec spec=switch(entity){
            case "users" -> new Spec("u.id,u.username,u.role,u.status,u.must_change_password,u.created_at", "users u", "u.username", "u.id DESC");
            case "comments" -> new Spec("m.*,u.username,g.title AS guide_title", "comments m JOIN users u ON u.id=m.user_id JOIN guides g ON g.id=m.guide_id", "m.body || ' ' || u.username || ' ' || g.title", "m.created_at DESC,m.id DESC");
            case "cities" -> new Spec("c.*", "cities c", "c.name || ' ' || c.province", "c.id DESC");
            case "spots" -> new Spec("s.*,c.name AS city_name", "spots s JOIN cities c ON c.id=s.city_id", "s.title || ' ' || c.name", "s.id DESC");
            case "guides" -> new Spec("g.*,c.name AS city_name", "guides g JOIN cities c ON c.id=g.city_id", "g.title || ' ' || c.name", "g.id DESC");
            case "foods" -> new Spec("f.*,c.name AS city_name", "foods f JOIN cities c ON c.id=f.city_id", "f.title || ' ' || c.name || ' ' || f.category", "f.id DESC");
            case "posts" -> new Spec("p.*,u.username,c.name AS city_name", "social_posts p JOIN users u ON u.id=p.user_id LEFT JOIN cities c ON c.id=p.city_id", "p.title || ' ' || p.body || ' ' || u.username", "p.id DESC");
            case "routes" -> new Spec("r.id,r.title,r.description,r.days,r.status,r.blocked,r.revision,r.created_at,r.updated_at,u.username", "shared_routes r JOIN users u ON u.id=r.user_id", "r.title || ' ' || coalesce(r.description,'') || ' ' || u.username", "r.id DESC");
            default -> throw new ApiError(404,"没有这个管理页面");
        };
        String like="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String condition=switch(entity){case "posts" -> " AND p.status<>'DELETED'";case "routes" -> " AND (r.status='PUBLISHED' OR r.blocked=1)";default -> "";};
        String where=" FROM "+spec.from()+" WHERE "+spec.filter()+" LIKE ? ESCAPE '\\'"+condition;
        final int requested=Math.max(1,page);
        return db.transaction(status->{
            long total=db.count("SELECT count(*)"+where,like); int pages=(int)Math.max(1,(total+size-1)/size); int actual=Math.min(requested,pages);
            var items=db.list("SELECT "+spec.select()+where+" ORDER BY "+spec.order()+" LIMIT ? OFFSET ?",like,size,(actual-1)*size);
            if(entity.equals("spots"))for(var row:items)row.put("gallery",db.decode(row.get("gallery")));
            if(entity.equals("guides"))for(var row:items){row.put("sections",db.decode(row.get("sections")));row.put("spot_ids",db.list("SELECT spot_id FROM guide_spots WHERE guide_id=? ORDER BY sort_order",row.get("id")).stream().map(v->v.get("spot_id")).toList());}
            return Map.of("items",items,"total",total,"page",actual,"size",size);
        });
    }
}
