package cn.edu.travel;

import java.util.*;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static cn.edu.travel.ApiController.*;

/** 预览可匿名；保存时重算，并原子写入行程、时间、约束、历史和幂等记录。 */
@RestController
@RequestMapping("/api")
public class ConstraintPlannerController {
    final Database db;final Auth auth;final FeatureController trips;
    public ConstraintPlannerController(Database db,Auth auth,FeatureController trips){this.db=db;this.auth=auth;this.trips=trips;}
    long integer(Object value,long min,long max,String label){if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min||((Number)value).longValue()>max)throw new ApiError(400,label+"请填写 "+min+"～"+max+" 的整数");return ((Number)value).longValue();}
    String string(Object value,String fallback,int max,String label){if(value==null)return fallback;if(!(value instanceof String s)||s.length()>max)throw new ApiError(400,label+"格式不正确");return s.trim();}
    boolean bool(Object value){if(!(value instanceof Boolean b))throw new ApiError(400,"必去状态请使用有效的选择");return b;}
    Map<String,Object> object(Object value,String label){if(!(value instanceof Map<?,?> raw))throw new ApiError(400,label+"格式不正确");var out=new LinkedHashMap<String,Object>();for(var e:raw.entrySet())out.put(String.valueOf(e.getKey()),e.getValue());return out;}
    void fields(Map<String,Object> value,Set<String> allowed){if(!allowed.containsAll(value.keySet()))throw new ApiError(400,"规划条件中包含不支持的字段，请重新生成");}
    int minute(String clock){if(!clock.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw new ApiError(400,"时间请使用有效的小时和分钟");return Integer.parseInt(clock.substring(0,2))*60+Integer.parseInt(clock.substring(3));}
    Map<String,Object> normalize(Map<String,Object> b){
        fields(b,Set.of("city_id","days","theme","objective","day_windows","candidates"));
        long city=integer(b.get("city_id"),1,Integer.MAX_VALUE,"目的地编号"),days=integer(b.get("days"),1,7,"旅行天数");
        String theme=string(b.get("theme"),"",20,"主题"),objective=string(b.get("objective"),"balanced",20,"规划偏好");
        if(!Set.of("","人文历史","山水风景","街巷生活").contains(theme)||!Set.of("balanced","interest","compact").contains(objective))throw new ApiError(400,"请选择有效的主题和规划偏好");
        if(!(b.get("day_windows") instanceof List<?> windows)||windows.size()!=days)throw new ApiError(400,"请为每一天设置一个起止时段");
        var cleanWindows=new ArrayList<Map<String,Object>>();Set<Long> usedDays=new HashSet<>();
        for(Object raw:windows){var w=object(raw,"每日时段");fields(w,Set.of("day_number","start_time","end_time"));long day=integer(w.get("day_number"),1,days,"日期编号");if(!usedDays.add(day))throw new ApiError(400,"每日时段的日期编号不能重复");String start=string(w.get("start_time"),"",5,"开始时间"),end=string(w.get("end_time"),"",5,"结束时间");if(minute(end)<=minute(start))throw new ApiError(400,"每天结束时间须晚于开始时间，暂不支持跨午夜时段");cleanWindows.add(Map.of("day_number",day,"start_time",start,"end_time",end));}
        cleanWindows.sort(Comparator.comparingLong(w->number(w.get("day_number"))));
        if(!(b.get("candidates") instanceof List<?> candidates)||candidates.isEmpty()||candidates.size()>18)throw new ApiError(400,"请选取 1～18 个候选景点");
        var cleanCandidates=new ArrayList<Map<String,Object>>();Set<Long> ids=new HashSet<>();
        for(Object raw:candidates){var c=object(raw,"候选景点");fields(c,Set.of("spot_id","required","fixed_day","priority","travel_minutes","rest_minutes"));long id=integer(c.get("spot_id"),1,Long.MAX_VALUE,"景点编号");if(!ids.add(id))throw new ApiError(400,"同一个候选景点不能重复");boolean required=bool(c.getOrDefault("required",false));long day=integer(c.getOrDefault("fixed_day",0),0,days,"固定日期"),priority=integer(c.getOrDefault("priority",3),1,5,"兴趣程度"),travel=integer(c.getOrDefault("travel_minutes",20),0,240,"交通分钟"),rest=integer(c.getOrDefault("rest_minutes",15),0,180,"休息分钟");var clean=new LinkedHashMap<String,Object>();clean.put("spot_id",id);clean.put("required",required||day>0);clean.put("fixed_day",day);clean.put("priority",priority);clean.put("travel_minutes",travel);clean.put("rest_minutes",rest);cleanCandidates.add(clean);}
        cleanCandidates.sort(Comparator.comparingLong(c->number(c.get("spot_id"))));
        var out=new LinkedHashMap<String,Object>();out.put("city_id",city);out.put("days",days);out.put("theme",theme);out.put("objective",objective);out.put("day_windows",cleanWindows);out.put("candidates",cleanCandidates);return out;
    }
    @GetMapping("/planner/options") Object options(@RequestParam(defaultValue="3") long city){var c=db.one("SELECT id,name FROM cities WHERE id=? AND published=1",city);return Map.of("city",c,"spots",db.list("SELECT s.id,s.title,s.image,s.city_id,s.theme,s.duration_minutes,c.name AS city_name FROM spots s JOIN cities c ON c.id=s.city_id WHERE s.city_id=? AND s.published=1 AND c.published=1 ORDER BY s.id",city),"candidate_limit",18);}
    List<Map<String,Object>> sources(Map<String,Object> config,boolean saving){
        var candidates=TripVersions.rows(config.get("candidates"));var values=candidates.stream().map(c->c.get("spot_id")).toArray();String marks=String.join(",",Collections.nCopies(values.length,"?"));
        var spots=db.list("SELECT s.id,s.title,s.image,s.city_id,s.theme,s.duration_minutes,s.published,c.name AS city_name FROM spots s JOIN cities c ON c.id=s.city_id WHERE s.id IN ("+marks+") AND c.published=1 ORDER BY s.id",values);
        if(spots.size()!=values.length||spots.stream().anyMatch(s->number(s.get("published"))!=1||number(s.get("city_id"))!=number(config.get("city_id"))))throw new ApiError(saving?409:400,"候选景点已下架、不存在或不属于当前目的地，请重新选择并生成");return spots;
    }
    String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(db.encode(TripVersions.normalize(value)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    String fingerprint(Map<String,Object> config,List<Map<String,Object>> spots){return hash(Map.of("engine","constraints-v1","config",config,"spots",spots));}
    @PostMapping("/planner/solve") Object solve(@RequestBody Map<String,Object> body){var config=normalize(body);return db.transaction(status->{db.one("SELECT id FROM cities WHERE id=? AND published=1",config.get("city_id"));var spots=sources(config,false);var result=new LinkedHashMap<String,Object>(ConstraintPlanner.solve(config,spots));result.put("fingerprint",fingerprint(config,spots));result.put("config",config);return result;});}
    @PostMapping("/planner/save") Object save(@RequestBody Map<String,Object> body,HttpServletRequest req){
        var user=auth.require(req);var config=normalize(object(body.get("config"),"规划条件"));String fingerprint=string(body.get("fingerprint"),"",64,"预览标记");if(!fingerprint.matches("[0-9a-f]{64}"))throw new ApiError(400,"请先生成有效的方案预览");
        long index=integer(body.get("plan_index"),0,2,"方案编号");String title=text(body,"title",1,60),start=string(body.get("start_date"),"",10,"出发日期"),key=string(body.get("request_key"),"",80,"保存编号");
        if(!key.matches("[A-Za-z0-9_-]{12,80}"))throw new ApiError(400,"保存编号无效，请重新打开保存窗口");if(!start.isEmpty())try{if(!LocalDate.parse(start).toString().equals(start))throw new IllegalArgumentException();}catch(Exception e){throw new ApiError(400,"请选择有效的出发日期");}
        String payloadHash=hash(Map.of("config",config,"fingerprint",fingerprint,"plan_index",index,"title",title,"start_date",start));
        return db.transaction(status->{
            // 先写入幂等键取得写锁，重复请求不会在读取后升级写锁时产生竞态。
            db.update("INSERT OR IGNORE INTO planner_saves(user_id,request_key,payload_hash) VALUES(?,?,?)",user.get("id"),key,payloadHash);
            var reservation=db.one("SELECT payload_hash,trip_id FROM planner_saves WHERE user_id=? AND request_key=?",user.get("id"),key);
            if(!payloadHash.equals(reservation.get("payload_hash")))throw new ApiError(409,"这次保存编号已用于另一份内容，请重新打开保存窗口");
            if(reservation.get("trip_id")!=null)return Map.of("id",reservation.get("trip_id"),"reused",true,"message","这份方案已经保存，已打开原行程");
            auth.require(req);var spots=sources(config,true);if(!fingerprint.equals(fingerprint(config,spots)))throw new ApiError(409,"景点资料或规划条件已变化，请重新生成并预览后再保存");
            var solved=ConstraintPlanner.solve(config,spots);var plans=TripVersions.rows(solved.get("plans"));if(!"feasible".equals(solved.get("status"))||index>=plans.size())throw new ApiError(409,"这份方案已经不可用，请重新生成");
            if(db.count("SELECT count(*) FROM trips WHERE user_id=?",user.get("id"))>=30)throw new ApiError(400,"最多保留 30 份行程，请先整理已有行程");
            var plan=plans.get((int)index);var items=TripVersions.rows(plan.get("items"));if(items.isEmpty())throw new ApiError(409,"方案中没有可保存的景点");
            long id=db.insert("INSERT INTO trips(user_id,title) VALUES(?,?)",user.get("id"),title);
            var payload=new LinkedHashMap<String,Object>();payload.put("title",title);payload.put("days",config.get("days"));payload.put("start_date",start);payload.put("notes","由约束规划生成。交通与休息使用填写的估计，游览时长采用编辑建议；出发前请核实开放、预约与交通情况。");payload.put("checklist",List.of());payload.put("items",items.stream().map(i->Map.of("spot_id",i.get("spot_id"),"day_number",i.get("day_number"),"note","")).toList());trips.save(id,payload,false);
            var schedule=Map.of("days",TripVersions.rows(plan.get("days")).stream().map(d->Map.of("day_number",d.get("day_number"),"start_time",d.get("start_time"))).toList(),"stops",items.stream().map(i->Map.of("spot_id",i.get("spot_id"),"travel_minutes",i.get("travel_minutes"),"rest_minutes",i.get("rest_minutes"))).toList());
            db.update("INSERT INTO trip_day_schedules(trip_id,settings) VALUES(?,?)",id,db.encode(schedule));
            var search=TripVersions.map(solved.get("search"));db.update("INSERT INTO planning_constraints(trip_id,config,fingerprint,plan_index,algorithm) VALUES(?,?,?,?,?)",id,db.encode(config),fingerprint,index,Objects.toString(search.get("algorithm"),"bounded constraint search"));
            TripVersions.capture(db,id,"CREATE",0);db.update("UPDATE planner_saves SET trip_id=? WHERE user_id=? AND request_key=?",id,user.get("id"),key);
            return Map.of("id",id,"reused",false,"message","方案已保存为新的私人行程，时间安排与规划条件一并保留");
        });
    }
    @GetMapping("/me/trips/{id}/planning") Object planning(@PathVariable long id,HttpServletRequest req){return db.transaction(status->{
        var user=auth.require(req);var t=db.one("SELECT id,days FROM trips WHERE id=? AND user_id=?",id,user.get("id"));var stored=db.list("SELECT config FROM planning_constraints WHERE trip_id=?",id);if(stored.isEmpty())return Map.of("configured",false);
        var config=TripVersions.map(db.decode(stored.get(0).get("config")));List<Map<String,String>> issues=new ArrayList<>();var summary=new ArrayList<String>();
        var windows=TripVersions.rows(config.get("day_windows"));var candidates=TripVersions.rows(config.get("candidates"));var rows=db.list("SELECT i.spot_id,i.day_number,s.title,s.duration_minutes,s.city_id,s.published FROM trip_items i JOIN spots s ON s.id=i.spot_id WHERE i.trip_id=? ORDER BY i.day_number,i.sort_order",id);Map<Long,Map<String,Object>> byId=new HashMap<>();for(var item:rows)byId.put(number(item.get("spot_id")),item);
        if(number(t.get("days"))!=number(config.get("days")))issues.add(issue("行程天数与原规划条件不同","可重新规划一份方案，或按当前天数检查每一天的安排。"));
        int required=0,fixed=0;for(var c:candidates){if(Boolean.TRUE.equals(c.get("required"))){required++;long sid=number(c.get("spot_id"));var item=byId.get(sid);if(item==null){var titles=db.list("SELECT title FROM spots WHERE id=?",sid);String name=titles.isEmpty()?"景点 #"+sid:Objects.toString(titles.get(0).get("title"));issues.add(issue("必去景点“"+name+"”已移出行程","可将它加回，或重新规划并调整必去条件。"));}else if(number(c.get("fixed_day"))>0&&number(item.get("day_number"))!=number(c.get("fixed_day")))issues.add(issue(item.get("title")+"没有安排在固定的第 "+c.get("fixed_day")+" 天","将景点移回指定日期，或重新规划。"));}if(number(c.get("fixed_day"))>0)fixed++;}
        summary.add("必去 "+required+" 个景点，其中 "+fixed+" 个固定日期。");
        var scheduleRows=db.list("SELECT settings FROM trip_day_schedules WHERE trip_id=?",id);var settings=scheduleRows.isEmpty()?Map.<String,Object>of():TripVersions.map(db.decode(scheduleRows.get(0).get("settings")));Map<Long,Map<String,Object>> times=new HashMap<>(),starts=new HashMap<>();for(var x:TripVersions.rows(settings.get("stops")))times.put(number(x.get("spot_id")),x);for(var x:TripVersions.rows(settings.get("days")))starts.put(number(x.get("day_number")),x);
        for(var w:windows){long day=number(w.get("day_number"));String begin=Objects.toString(w.get("start_time")),end=Objects.toString(w.get("end_time"));summary.add("第 "+day+" 天："+begin+"—"+end);if(day>number(t.get("days")))continue;var dayItems=rows.stream().filter(i->number(i.get("day_number"))==day).toList();if(dayItems.isEmpty())continue;String actual=Objects.toString(starts.getOrDefault(day,Map.of()).getOrDefault("start_time","09:00"));long finish=minute(actual);for(var i:dayItems){var s=times.getOrDefault(number(i.get("spot_id")),Map.of());finish+=number(i.get("duration_minutes"))+number(s.getOrDefault("travel_minutes",0))+number(s.getOrDefault("rest_minutes",0));}
            if(minute(actual)<minute(begin))issues.add(issue("第 "+day+" 天早于允许的开始时间","原条件从 "+begin+" 开始，请调整当天出发时间。"));
            if(finish>minute(end))issues.add(issue("第 "+day+" 天超过截止时间约 "+(finish-minute(end))+" 分钟","可移动部分景点、调整交通休息，或重新规划。"));
        }
        for(var i:rows){if(number(i.get("city_id"))!=number(config.get("city_id")))issues.add(issue(i.get("title")+"不在原规划目的地","跨城交通需单独确认，可重新规划或自行调整。"));if(number(i.get("published"))==0)issues.add(issue(i.get("title")+"当前已下架","请核实该景点可用情况，再决定是否保留。"));}
        summary.add("交通与休息由你估计，停留时长采用当前编辑建议。");return Map.of("configured",true,"config",config,"issues",issues,"summary",summary);
    });}
    Map<String,String> issue(String message,String suggestion){return Map.of("message",message,"suggestion",suggestion);}
}
