package cn.edu.travel;

import java.util.*;
import java.time.LocalDate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static cn.edu.travel.ApiController.*;

/** 行程归属于当前账号；整份保存使用事务与版本号，防止多窗口互相覆盖。 */
@RestController
@RequestMapping("/api")
public class FeatureController {
    final Database db; final Auth auth;
    public FeatureController(Database db,Auth auth){this.db=db;this.auth=auth;}
    Map<String,Object> owned(long id,HttpServletRequest req) {
        var user=auth.require(req);
        return db.one("SELECT * FROM trips WHERE id=? AND user_id=?",id,user.get("id"));
    }
    @GetMapping("/me/trips") Object trips(HttpServletRequest req) {
        var user=auth.require(req);
        return db.list("SELECT t.*,(SELECT count(*) FROM trip_items i WHERE i.trip_id=t.id) AS item_count,(SELECT s.image FROM trip_items i JOIN spots s ON s.id=i.spot_id WHERE i.trip_id=t.id ORDER BY i.day_number,i.sort_order LIMIT 1) AS image FROM trips t WHERE user_id=? ORDER BY t.updated_at DESC,t.id DESC",user.get("id"));
    }
    @GetMapping("/me/trips/{id}") Object trip(@PathVariable long id,HttpServletRequest req) {
        var t=owned(id,req);
        t.put("checklist",db.decode(t.get("checklist")));
        t.put("items",db.list("SELECT i.spot_id,i.day_number,i.sort_order,i.note,s.title,s.image,s.published,s.duration_minutes,c.name AS city_name FROM trip_items i JOIN spots s ON s.id=i.spot_id JOIN cities c ON c.id=s.city_id WHERE i.trip_id=? ORDER BY i.day_number,i.sort_order,i.id",id));
        return t;
    }
    @PostMapping("/me/trips") Object create(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        var user=auth.require(req);
        long id=db.transaction(status -> {
            if(db.count("SELECT count(*) FROM trips WHERE user_id=?",user.get("id"))>=30)throw new ApiError(400,"最多保留 30 份行程，请先整理已有行程");
            long key=db.insert("INSERT INTO trips(user_id,title) VALUES(?,?)",user.get("id"),text(body,"title",1,60));
            save(key,body,false);TripVersions.capture(db,key,"CREATE",0);return key;
        });
        return Map.of("id",id,"message","行程已创建");
    }
    @PutMapping("/me/trips/{id}") Object edit(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        db.transaction(status->{owned(id,req);TripVersions.ensure(db,id);save(id,body,true);TripVersions.capture(db,id,"EDIT",0);return null;});
        return trip(id,req);
    }
    @DeleteMapping("/me/trips/{id}") Object delete(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        var user=auth.require(req);long revision=positiveRevision(body);
        return db.transaction(status->{
            int changed=db.update("DELETE FROM trips WHERE id=? AND user_id=? AND revision=?",id,user.get("id"),revision);
            if(changed==0){owned(id,req);throw new ApiError(409,"行程已在另一个窗口更新，请刷新后重新确认删除");}
            return Map.of("message","行程已删除");
        });
    }
    public void save(long id,Map<String,Object> body,boolean checkVersion) {
        String title=text(body,"title",1,60),notes=text(body,"notes",0,1500),date=text(body,"start_date",0,10);
        if(!date.isEmpty())try{if(!LocalDate.parse(date).toString().equals(date))throw new IllegalArgumentException();}catch(Exception e){throw new ApiError(400,"请选择有效的出发日期");}
        long days=number(body.getOrDefault("days",1));if(days<1 || days>14)throw new ApiError(400,"行程天数请设置为 1～14 天");
        Object value=body.getOrDefault("items",List.of());
        if(!(value instanceof List<?> rows) || rows.size()>80)throw new ApiError(400,"一份行程最多安排 80 个景点");
        Set<Long> old=new HashSet<>();
        for(var row:db.list("SELECT spot_id FROM trip_items WHERE trip_id=?",id))old.add(number(row.get("spot_id")));
        Set<Long> seen=new HashSet<>(); List<Map<String,Object>> clean=new ArrayList<>();
        for(Object row:(List<?>)value) {
            if(!(row instanceof Map<?,?>))throw new ApiError(400,"行程站点格式不正确");
            @SuppressWarnings("unchecked") Map<String,Object> r=(Map<String,Object>)row;
            long spot=number(r.get("spot_id")),day=number(r.get("day_number"));
            if(day<1 || day>days)throw new ApiError(400,"景点所在天数超出行程范围，请先移动景点");
            if(!seen.add(spot))throw new ApiError(400,"同一个景点在这份行程中只需添加一次");
            if(db.count("SELECT count(*) FROM spots s JOIN cities c ON c.id=s.city_id WHERE s.id=? AND ((s.published=1 AND c.published=1) OR ?=1)",spot,old.contains(spot)?1:0)==0)throw new ApiError(400,"景点已下架或不存在，请重新选择");
            clean.add(Map.of("spot_id",spot,"day",day,"note",text(r,"note",0,500)));
        }
        Object checklist=body.getOrDefault("checklist",db.decode(db.one("SELECT checklist FROM trips WHERE id=?",id).get("checklist")));
        if(!(checklist instanceof List<?> tasks)||tasks.size()>30)throw new ApiError(400,"行前清单最多保留 30 项");
        List<Map<String,Object>> cleanTasks=new ArrayList<>();Set<String> keys=new HashSet<>();
        for(Object v:(List<?>)checklist){
            if(!(v instanceof Map<?,?> task))throw new ApiError(400,"清单格式不正确");
            String key=Objects.toString(task.get("key"),""),label=Objects.toString(task.get("text"),"").trim();
            if(key.isBlank()||key.length()>60||!keys.add(key)||label.isBlank()||label.length()>80||!(task.get("done") instanceof Boolean))throw new ApiError(400,"清单内容请控制在 1～80 字，每项需有独立编号和完成状态");
            cleanTasks.add(Map.of("key",key,"text",label,"done",task.get("done")));
        }
        int changed;
        if(checkVersion) changed=db.update("UPDATE trips SET title=?,days=?,start_date=?,notes=?,checklist=?,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND revision=?",title,days,date,notes,db.encode(cleanTasks),id,number(body.get("revision")));
        else changed=db.update("UPDATE trips SET title=?,days=?,start_date=?,notes=?,checklist=? WHERE id=?",title,days,date,notes,db.encode(cleanTasks),id);
        if(changed==0)throw new ApiError(409,"行程已在另一个窗口更新，请刷新后再编辑");
        db.update("DELETE FROM trip_items WHERE trip_id=?",id);
        int order=0;
        for(var r:clean)db.update("INSERT INTO trip_items(trip_id,spot_id,day_number,sort_order,note) VALUES(?,?,?,?,?)",id,r.get("spot_id"),r.get("day"),order++,r.get("note"));
    }
    @GetMapping("/admin/overview") Object overview(HttpServletRequest req) {
        auth.admin(req);Map<String,Object> out=new LinkedHashMap<>();
        for(String table:List.of("cities","spots","guides","users","comments","trips","foods"))out.put(table,db.count("SELECT count(*) FROM "+table));
        out.put("published_spots",db.count("SELECT count(*) FROM spots a JOIN cities c ON c.id=a.city_id WHERE a.published=1 AND c.published=1"));
        out.put("published_guides",db.count("SELECT count(*) FROM guides a JOIN cities c ON c.id=a.city_id WHERE a.published=1 AND c.published=1"));
        out.put("banned_users",db.count("SELECT count(*) FROM users WHERE status='BANNED'"));
        out.put("favorites",db.count("SELECT (SELECT count(*) FROM spot_favorites)+(SELECT count(*) FROM guide_favorites)+(SELECT count(*) FROM food_favorites)"));
        out.put("published_foods",db.count("SELECT count(*) FROM foods a JOIN cities c ON c.id=a.city_id WHERE a.published=1 AND c.published=1"));
        out.put("published_posts",db.count("SELECT count(*) FROM social_posts WHERE status='PUBLISHED'"));
        out.put("social_comments",db.count("SELECT count(*) FROM social_comments m JOIN social_posts p ON p.id=m.post_id WHERE p.status='PUBLISHED'"));
        out.put("pending_feedback",db.count("SELECT count(*) FROM feedback WHERE status='PENDING'"));
        out.put("popular",db.list("SELECT s.id,s.title,c.name AS city_name,'spot' AS type,count(f.user_id) AS favorites FROM spots s JOIN cities c ON c.id=s.city_id JOIN spot_favorites f ON f.spot_id=s.id WHERE s.published=1 AND c.published=1 GROUP BY s.id UNION ALL SELECT g.id,g.title,c.name AS city_name,'guide' AS type,count(f.user_id) AS favorites FROM guides g JOIN cities c ON c.id=g.city_id JOIN guide_favorites f ON f.guide_id=g.id WHERE g.published=1 AND c.published=1 GROUP BY g.id UNION ALL SELECT a.id,a.title,c.name AS city_name,'food' AS type,count(f.user_id) AS favorites FROM foods a JOIN cities c ON c.id=a.city_id JOIN food_favorites f ON f.food_id=a.id WHERE a.published=1 AND c.published=1 GROUP BY a.id ORDER BY favorites DESC,title LIMIT 8"));
        out.put("regions",db.list("SELECT c.name,(SELECT count(*) FROM spots s WHERE s.city_id=c.id AND published=1) AS spots,(SELECT count(*) FROM guides g WHERE g.city_id=c.id AND published=1) AS guides,(SELECT count(*) FROM foods f WHERE f.city_id=c.id AND published=1) AS foods FROM cities c ORDER BY sort_order,id"));
        return out;
    }
    @GetMapping("/me/history") Object history(HttpServletRequest req) {
        var user=auth.require(req);
        return db.list("SELECT 'spot' AS type,s.id,s.title,s.intro,s.image,s.published,c.name AS city_name,h.visited_at AS visited_at FROM browse_history h JOIN spots s ON s.id=h.spot_id JOIN cities c ON c.id=s.city_id WHERE h.user_id=? UNION ALL SELECT 'guide' AS type,g.id,g.title,g.intro,g.image,g.published,c.name AS city_name,h.visited_at AS visited_at FROM browse_history h JOIN guides g ON g.id=h.guide_id JOIN cities c ON c.id=g.city_id WHERE h.user_id=? ORDER BY visited_at DESC",user.get("id"),user.get("id"));
    }
    @PostMapping("/me/history/{type}/{id}") Object record(@PathVariable String type,@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);String name=table(type);db.one(publicLookup(name),id);
        db.transaction(status->{
            db.update("INSERT INTO browse_history(user_id,"+type+"_id) VALUES(?,?) ON CONFLICT(user_id,"+type+"_id) DO UPDATE SET visited_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')",user.get("id"),id);
            db.update("DELETE FROM browse_history WHERE user_id=? AND id NOT IN (SELECT id FROM browse_history WHERE user_id=? ORDER BY visited_at DESC,id DESC LIMIT 30)",user.get("id"),user.get("id"));return null;
        });return Map.of("message","浏览记录已保存");
    }
    @DeleteMapping("/me/history") Object clearHistory(HttpServletRequest req) {
        var user=auth.require(req);db.update("DELETE FROM browse_history WHERE user_id=?",user.get("id"));return Map.of("message","浏览记录已清空");
    }
    @PutMapping("/comments/{id}") Object editComment(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        var user=auth.require(req);var c=db.one("SELECT user_id,guide_id FROM comments WHERE id=?",id);
        if(number(c.get("user_id"))!=number(user.get("id")))throw new ApiError(403,"只能修改自己发表的评论");
        db.update("UPDATE comments SET body=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",text(body,"body",1,1000),id);
        return Map.of("message","评论已修改");
    }
}
