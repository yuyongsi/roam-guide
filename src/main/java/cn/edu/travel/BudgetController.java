package cn.edu.travel;

import java.util.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static cn.edu.travel.ApiController.*;

/** 金额以整数分保存；预算版本独立于行程版本，避免相互覆盖。 */
@RestController
@RequestMapping("/api/me/trips/{tripId}/budget")
public class BudgetController {
    final Database db; final Auth auth;
    static final List<String> CATEGORIES=List.of("交通","住宿","餐饮","门票","购物","其他");
    public BudgetController(Database db,Auth auth){this.db=db;this.auth=auth;}
    private void owned(long tripId,HttpServletRequest req){var u=auth.require(req);db.one("SELECT id FROM trips WHERE id=? AND user_id=?",tripId,u.get("id"));}
    static long cents(Object value){
        String s=Objects.toString(value,"").trim();
        if(!s.matches("[0-9]{1,10}(?:\\.[0-9]{1,2})?"))throw new ApiError(400,"金额请填写非负数字，最多保留两位小数");
        try{long n=new BigDecimal(s).movePointRight(2).longValueExact();if(n>100000000L)throw new ArithmeticException();return n;}
        catch(ArithmeticException e){throw new ApiError(400,"单项金额不能超过 1000000 元");}
    }
    static String amount(long n){return BigDecimal.valueOf(n,2).toPlainString();}
    @GetMapping Object get(@PathVariable long tripId,HttpServletRequest req){return db.transaction(status->{owned(tripId,req);return view(tripId);});}
    private Map<String,Object> view(long id){
        var rows=db.list("SELECT * FROM trip_budgets WHERE trip_id=?",id);
        int people=rows.isEmpty()?1:((Number)rows.get(0).get("people")).intValue();
        long revision=rows.isEmpty()?0:number(rows.get(0).get("revision"));
        long tripDays=number(db.one("SELECT days FROM trips WHERE id=?",id).get("days"));
        var entries=db.list("SELECT b.item_key AS key,b.label,b.category,b.planned_cents,b.actual_cents,b.day_number,b.spot_id,s.title AS spot_title,EXISTS(SELECT 1 FROM trip_items i WHERE i.trip_id=b.trip_id AND i.spot_id=b.spot_id) AS spot_in_trip FROM trip_budget_items b LEFT JOIN spots s ON s.id=b.spot_id WHERE b.trip_id=? ORDER BY b.sort_order,b.item_key",id);
        long planned=0,actual=0;Map<String,long[]> sums=new LinkedHashMap<>();for(String c:CATEGORIES)sums.put(c,new long[2]);
        Map<Long,long[]> daySums=new TreeMap<>();daySums.put(0L,new long[2]);for(long day=1;day<=tripDays;day++)daySums.put(day,new long[2]);
        for(var e:entries){long p=number(e.get("planned_cents")),a=number(e.get("actual_cents")),day=number(e.get("day_number"));e.put("planned",amount(p));e.put("actual",amount(a));e.put("day_valid",day==0||day<=tripDays);planned+=p;actual+=a;sums.get(e.get("category"))[0]+=p;sums.get(e.get("category"))[1]+=a;long[] ds=daySums.computeIfAbsent(day,k->new long[2]);ds[0]+=p;ds[1]+=a;}
        List<Map<String,Object>> daily=new ArrayList<>();for(var e:daySums.entrySet())daily.add(Map.of("day_number",e.getKey(),"planned_cents",e.getValue()[0],"actual_cents",e.getValue()[1],"valid",e.getKey()<=tripDays));
        List<Map<String,Object>> categories=new ArrayList<>();for(var e:sums.entrySet())categories.add(Map.of("category",e.getKey(),"planned_cents",e.getValue()[0],"actual_cents",e.getValue()[1]));
        long perPlanned=BigDecimal.valueOf(planned).divide(BigDecimal.valueOf(people),0,RoundingMode.HALF_UP).longValueExact();
        long perActual=BigDecimal.valueOf(actual).divide(BigDecimal.valueOf(people),0,RoundingMode.HALF_UP).longValueExact();
        Map<String,Object> out=new LinkedHashMap<>();out.put("trip_id",id);out.put("people",people);out.put("revision",revision);out.put("notes",rows.isEmpty()?"":rows.get(0).get("notes"));out.put("updated_at",rows.isEmpty()?null:rows.get(0).get("updated_at"));out.put("entries",entries);
        out.put("trip_days",tripDays);out.put("trip_spots",db.list("SELECT i.spot_id,s.title,i.day_number FROM trip_items i JOIN spots s ON s.id=i.spot_id WHERE i.trip_id=? ORDER BY i.day_number,i.sort_order",id));
        out.put("summary",Map.of("planned_cents",planned,"actual_cents",actual,"balance_cents",planned-actual,"per_person_planned_cents",perPlanned,"per_person_actual_cents",perActual,"categories",categories,"days",daily));return out;
    }
    @PutMapping Object save(@PathVariable long tripId,@RequestBody Map<String,Object> body,HttpServletRequest req){
        return db.transaction(status->{
            owned(tripId,req);long people=number(body.get("people")),revision=number(body.get("revision"));
            if(people<1||people>99)throw new ApiError(400,"同行人数请填写 1～99 人");if(revision<0)throw new ApiError(400,"预算版本不正确，请刷新后重试");
            String notes=text(body,"notes",0,500);Object list=body.get("entries");
            if(!(list instanceof List<?> items)||items.size()>80)throw new ApiError(400,"预算明细最多保留 80 项");
            long tripDays=number(db.one("SELECT days FROM trips WHERE id=?",tripId).get("days"));
            Map<String,Map<String,Object>> old=new HashMap<>();for(var r:db.list("SELECT item_key,day_number,spot_id FROM trip_budget_items WHERE trip_id=?",tripId))old.put(Objects.toString(r.get("item_key")),r);
            Set<String> keys=new HashSet<>();List<Map<String,Object>> clean=new ArrayList<>();
            for(Object item:(List<?>)list){
                if(!(item instanceof Map<?,?>))throw new ApiError(400,"预算明细格式不正确");
                @SuppressWarnings("unchecked") Map<String,Object> e=(Map<String,Object>)item;
                String key=text(e,"key",1,64),label=text(e,"label",1,80),category=text(e,"category",1,10);
                if(!key.matches("[A-Za-z0-9_-]+")||!keys.add(key))throw new ApiError(400,"预算明细编号重复或不正确");
                if(!CATEGORIES.contains(category))throw new ApiError(400,"请选择有效的费用分类");
                long day=number(e.getOrDefault("day_number",0)),spot=number(e.getOrDefault("spot_id",0));var previous=old.get(key);
                boolean preservedDay=previous!=null&&day==number(previous.get("day_number")),preservedSpot=previous!=null&&spot==number(previous.get("spot_id"));
                if(day<0||day>14||(day>tripDays&&!preservedDay))throw new ApiError(400,"费用关联的天数超出当前行程范围");
                if(spot<0||(spot>0&&!preservedSpot&&db.count("SELECT count(*) FROM trip_items WHERE trip_id=? AND spot_id=?",tripId,spot)==0))throw new ApiError(400,"费用只能关联当前行程中的景点");
                clean.add(Map.of("key",key,"label",label,"category",category,"planned",cents(e.get("planned")),"actual",cents(e.get("actual")),"day_number",day,"spot_id",spot));
            }
            int changed;
            if(revision==0)changed=db.update("INSERT OR IGNORE INTO trip_budgets(trip_id,people,notes,revision) VALUES(?,?,?,1)",tripId,people,notes);
            else changed=db.update("UPDATE trip_budgets SET people=?,notes=?,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE trip_id=? AND revision=?",people,notes,tripId,revision);
            if(changed==0)throw new ApiError(409,"预算已在另一个窗口更新，请刷新行程后再编辑");
            db.update("DELETE FROM trip_budget_items WHERE trip_id=?",tripId);int order=0;
            for(var e:clean)db.update("INSERT INTO trip_budget_items(trip_id,item_key,label,category,planned_cents,actual_cents,sort_order,day_number,spot_id) VALUES(?,?,?,?,?,?,?,?,?)",tripId,e.get("key"),e.get("label"),e.get("category"),e.get("planned"),e.get("actual"),order++,e.get("day_number"),e.get("spot_id"));
            return view(tripId);
        });
    }
}
