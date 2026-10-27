package cn.edu.travel;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static cn.edu.travel.ApiController.*;

/** 用餐计划只向行程本人开放；明确确认后才向预算同步，不重复累计。 */
@RestController
@RequestMapping("/api/me/trips/{tripId}/meals")
public class TripMealController {
    final Database db; final Auth auth;
    static final Set<String> SLOTS=Set.of("早餐","午餐","晚餐","加餐");
    public TripMealController(Database db,Auth auth){this.db=db;this.auth=auth;}
    private Map<String,Object> owned(long tripId,HttpServletRequest req){var u=auth.require(req);return db.one("SELECT id,days FROM trips WHERE id=? AND user_id=?",tripId,u.get("id"));}
    private Map<String,Object> meal(long tripId,long id){return db.one("SELECT * FROM trip_meals WHERE trip_id=? AND id=?",tripId,id);}
    @GetMapping Object list(@PathVariable long tripId,HttpServletRequest req){
        var trip=owned(tripId,req);
        var items=db.list("SELECT m.*,f.title,f.image,f.published,f.city_id,c.name AS city_name,s.title AS spot_title,EXISTS(SELECT 1 FROM trip_items i WHERE i.trip_id=m.trip_id AND i.spot_id=m.spot_id) AS spot_in_trip,b.planned_cents AS budget_planned_cents,b.actual_cents AS budget_actual_cents FROM trip_meals m JOIN foods f ON f.id=m.food_id JOIN cities c ON c.id=f.city_id LEFT JOIN spots s ON s.id=m.spot_id LEFT JOIN trip_budget_items b ON b.trip_id=m.trip_id AND b.item_key=m.budget_key WHERE m.trip_id=? ORDER BY m.day_number,CASE m.slot WHEN '早餐' THEN 1 WHEN '午餐' THEN 2 WHEN '晚餐' THEN 3 ELSE 4 END,m.id",tripId);
        for(var m:items)m.put("planned",BudgetController.amount(number(m.get("planned_cents"))));
        return Map.of("items",items,"trip_days",trip.get("days"),"budget_revision",db.count("SELECT coalesce(max(revision),0) FROM trip_budgets WHERE trip_id=?",tripId));
    }
    @PostMapping Object create(@PathVariable long tripId,@RequestBody Map<String,Object> body,HttpServletRequest req){return save(tripId,0,body,req);}
    @PutMapping("/{id}") Object edit(@PathVariable long tripId,@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req){return save(tripId,id,body,req);}
    private Object save(long tripId,long id,Map<String,Object> body,HttpServletRequest req){return db.transaction(status->{
        var trip=owned(tripId,req);var old=id==0?null:meal(tripId,id);
        long day=number(body.get("day_number")),food=number(body.get("food_id")),spot=number(body.getOrDefault("spot_id",0));
        if(day<1||day>number(trip.get("days")))throw new ApiError(400,"请把用餐安排在当前行程天数内");
        String slot=text(body,"slot",1,5),notes=text(body,"notes",0,500);if(!SLOTS.contains(slot))throw new ApiError(400,"请选择早餐、午餐、晚餐或加餐");
        var f=db.one("SELECT f.id,f.city_id,CASE WHEN c.published=1 THEN f.published ELSE 0 END AS published FROM foods f JOIN cities c ON c.id=f.city_id WHERE f.id=?",food);
        if(number(f.get("published"))==0&&(old==null||food!=number(old.get("food_id"))))throw new ApiError(400,"这道美食已下架，请选择其他美食");
        if(spot<0||spot>0&&db.count("SELECT count(*) FROM trip_items i JOIN spots s ON s.id=i.spot_id WHERE i.trip_id=? AND i.spot_id=? AND s.city_id=?",tripId,spot,f.get("city_id"))==0)throw new ApiError(400,"请选择当前行程中与美食同城的景点，或取消景点关联");
        long planned=BudgetController.cents(body.get("planned"));
        if(db.count("SELECT count(*) FROM trip_meals WHERE trip_id=? AND food_id=? AND day_number=? AND slot=? AND id<>?",tripId,food,day,slot,id)>0)throw new ApiError(409,"同一天这一餐已安排这道美食，可直接编辑已有计划");
        long key=id;
        if(id==0){if(db.count("SELECT count(*) FROM trip_meals WHERE trip_id=?",tripId)>=42)throw new ApiError(400,"每份行程最多保留 42 项用餐计划");key=db.insert("INSERT INTO trip_meals(trip_id,food_id,day_number,slot,spot_id,planned_cents,notes,budget_key) VALUES(?,?,?,?,?,?,?,?)",tripId,food,day,slot,spot,planned,notes,"meal_"+UUID.randomUUID());}
        else if(db.update("UPDATE trip_meals SET food_id=?,day_number=?,slot=?,spot_id=?,planned_cents=?,notes=?,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE trip_id=? AND id=? AND revision=?",food,day,slot,spot,planned,notes,tripId,id,number(body.get("revision")))==0)throw new ApiError(409,"用餐计划已在另一个窗口更新，请刷新后再编辑");
        return Map.of("id",key,"message","用餐计划已保存，预计餐费尚未自动计入预算");
    });}
    @DeleteMapping("/{id}") Object delete(@PathVariable long tripId,@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req){return db.transaction(status->{owned(tripId,req);meal(tripId,id);if(db.update("DELETE FROM trip_meals WHERE trip_id=? AND id=? AND revision=?",tripId,id,number(body.get("revision")))==0)throw new ApiError(409,"用餐计划已更新，请刷新后再移除");return Map.of("message","用餐计划已移除，已记录的预算费用继续保留");});}
    @PostMapping("/{id}/budget") Object toBudget(@PathVariable long tripId,@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req){return db.transaction(status->{
        var trip=owned(tripId,req);var m=meal(tripId,id);if(number(m.get("revision"))!=number(body.get("revision")))throw new ApiError(409,"用餐计划已更新，请刷新后再计入预算");
        if(number(m.get("day_number"))>number(trip.get("days")))throw new ApiError(400,"这项用餐计划超出当前天数，请先重新安排");
        var f=db.one("SELECT f.title,CASE WHEN c.published=1 THEN f.published ELSE 0 END AS published FROM foods f JOIN cities c ON c.id=f.city_id WHERE f.id=?",m.get("food_id"));if(number(f.get("published"))==0)throw new ApiError(400,"这道美食已下架，请先调整计划");
        long expected=number(body.get("budget_revision"));
        int changed=expected==0?db.update("INSERT OR IGNORE INTO trip_budgets(trip_id,people,notes,revision) VALUES(?,1,'',1)",tripId):db.update("UPDATE trip_budgets SET revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE trip_id=? AND revision=?",tripId,expected);
        if(changed==0)throw new ApiError(409,"预算已在另一个窗口更新，请刷新后再计入");
        boolean exists=db.count("SELECT count(*) FROM trip_budget_items WHERE trip_id=? AND item_key=?",tripId,m.get("budget_key"))>0;
        if(!exists&&db.count("SELECT count(*) FROM trip_budget_items WHERE trip_id=?",tripId)>=80)throw new ApiError(400,"预算已达到 80 项，请先整理明细");
        long spot=number(m.get("spot_id"));if(spot>0&&db.count("SELECT count(*) FROM trip_items WHERE trip_id=? AND spot_id=?",tripId,spot)==0)spot=0;
        String label=f.get("title")+"（"+m.get("slot")+"）";
        if(label.length()>80)label=label.substring(0,80);
        db.update("INSERT INTO trip_budget_items(trip_id,item_key,label,category,planned_cents,actual_cents,sort_order,day_number,spot_id) VALUES(?,?,?,'餐饮',?,0,(SELECT coalesce(max(sort_order),-1)+1 FROM trip_budget_items WHERE trip_id=?),?,?) ON CONFLICT(trip_id,item_key) DO UPDATE SET label=excluded.label,category='餐饮',planned_cents=excluded.planned_cents,day_number=excluded.day_number,spot_id=excluded.spot_id",tripId,m.get("budget_key"),label,m.get("planned_cents"),tripId,m.get("day_number"),spot);
        return Map.of("message",exists?"已同步这笔餐费的计划预算，实际支出保留":"预计餐费已计入预算，实际支出请在费用明细填写");
    });}
}
