package cn.edu.travel;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static cn.edu.travel.ApiController.*;

/** 每日时间安排仅本人可读写；时间为用户计划，不包含实时交通推算。 */
@RestController
@RequestMapping("/api")
public class PlannerDepthController {
    final Database db; final Auth auth;
    public PlannerDepthController(Database db,Auth auth){this.db=db;this.auth=auth;}
    Map<String,Object> owned(long id,HttpServletRequest req){
        return db.one("SELECT * FROM trips WHERE id=? AND user_id=?",id,auth.require(req).get("id"));
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> settings(Object value){
        Object parsed=db.decode(value);
        return parsed instanceof Map<?,?>?(Map<String,Object>)parsed:Map.of("days",List.of(),"stops",List.of());
    }
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> rows(Map<String,Object> data,String key){
        Object value=data.get(key);if(!(value instanceof List<?>))return List.of();
        return ((List<?>)value).stream().filter(x->x instanceof Map<?,?>).map(x->(Map<String,Object>)x).toList();
    }
    private long minutes(Map<String,Object> row,String key,long max){
        long n=number(row.getOrDefault(key,0));
        if(n<0||n>max)throw new ApiError(400,"交通和休息时间请填写 0～360 分钟");return n;
    }
    private String clock(long minute){
        return (minute>=1440?"第 "+(minute/1440+1)+" 日 ":"")+String.format(java.util.Locale.ROOT,"%02d:%02d",(minute%1440)/60,minute%60);
    }
    @GetMapping("/me/trips/{id}/schedule") Object get(@PathVariable long id,HttpServletRequest req){return view(id,owned(id,req));}
    Object view(long id,Map<String,Object> trip){
        var stored=db.list("SELECT * FROM trip_day_schedules WHERE trip_id=?",id);
        Map<String,Object> conf=stored.isEmpty()?Map.of("days",List.of(),"stops",List.of()):settings(stored.get(0).get("settings"));
        Map<Long,Map<String,Object>> starts=new HashMap<>(),stops=new HashMap<>();
        for(var r:rows(conf,"days"))starts.put(number(r.get("day_number")),r);
        for(var r:rows(conf,"stops"))stops.put(number(r.get("spot_id")),r);
        var items=db.list("SELECT i.spot_id,i.day_number,s.title,s.duration_minutes FROM trip_items i JOIN spots s ON s.id=i.spot_id WHERE i.trip_id=? ORDER BY i.day_number,i.sort_order,i.id",id);
        List<Map<String,Object>> days=new ArrayList<>();
        for(long d=1;d<=number(trip.get("days"));d++){
            String start=Objects.toString(starts.getOrDefault(d,Map.of()).getOrDefault("start_time","09:00"));
            long cursor=Long.parseLong(start.substring(0,2))*60+Long.parseLong(start.substring(3)),begin=cursor;
            List<Map<String,Object>> timeline=new ArrayList<>();long travel=0,rest=0,visit=0;
            for(var item:items){
                if(number(item.get("day_number"))!=d)continue;
                var saved=stops.getOrDefault(number(item.get("spot_id")),Map.of());
                long tr=number(saved.getOrDefault("travel_minutes",0)),re=number(saved.getOrDefault("rest_minutes",0));
                long duration=number(item.get("duration_minutes"));
                Map<String,Object> stop=new LinkedHashMap<>();stop.put("spot_id",item.get("spot_id"));stop.put("title",item.get("title"));
                stop.put("travel_minutes",tr);stop.put("rest_minutes",re);stop.put("visit_minutes",duration);
                stop.put("travel_start",clock(cursor));cursor+=tr;stop.put("arrival",clock(cursor));cursor+=duration;
                stop.put("departure",clock(cursor));cursor+=re;stop.put("rest_end",clock(cursor));timeline.add(stop);
                travel+=tr;rest+=re;visit+=duration;
            }
            var day=new LinkedHashMap<String,Object>();day.put("day_number",d);day.put("start_time",start);day.put("end_time",clock(cursor));day.put("total_minutes",cursor-begin);day.put("travel_minutes",travel);day.put("rest_minutes",rest);day.put("visit_minutes",visit);day.put("crosses_midnight",cursor>=1440);day.put("stops",timeline);days.add(day);
        }
        return Map.of("trip_id",id,"trip_revision",trip.get("revision"),"revision",stored.isEmpty()?0:stored.get(0).get("revision"),"days",days);
    }
    @PutMapping("/me/trips/{id}/schedule") Object put(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req){
        return db.transaction(status->{
            var t=owned(id,req);TripVersions.ensure(db,id);
            if(number(body.get("trip_revision"))!=number(t.get("revision")))throw new ApiError(409,"行程已在其他窗口变更，请刷新后设置时间");
            Object dv=body.get("days"),sv=body.get("stops");
            if(!(dv instanceof List<?> dayRows)||dayRows.size()>14||!(sv instanceof List<?> stopRows)||stopRows.size()>80)throw new ApiError(400,"每日时间安排格式不正确");
            Set<Long> validSpots=new HashSet<>();for(var r:db.list("SELECT spot_id FROM trip_items WHERE trip_id=?",id))validSpots.add(number(r.get("spot_id")));
            Set<Long> usedDays=new HashSet<>(),usedSpots=new HashSet<>();List<Map<String,Object>> cleanDays=new ArrayList<>(),cleanStops=new ArrayList<>();
            for(Object v:dayRows){
                if(!(v instanceof Map<?,?> r))throw new ApiError(400,"每日时间安排格式不正确");
                long day=number(r.get("day_number"));String start=Objects.toString(r.get("start_time"),"");
                if(day<1||day>number(t.get("days"))||!usedDays.add(day))throw new ApiError(400,"每日编号超出行程范围或重复");
                if(!start.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw new ApiError(400,"出发时间请使用有效的小时和分钟");
                cleanDays.add(Map.of("day_number",day,"start_time",start));
            }
            for(Object v:stopRows){
                if(!(v instanceof Map<?,?>))throw new ApiError(400,"站点时间格式不正确");
                @SuppressWarnings("unchecked") var row=(Map<String,Object>)v;long spot=number(row.get("spot_id"));
                if(!validSpots.contains(spot)||!usedSpots.add(spot))throw new ApiError(400,"只能设置这份行程内的景点时间，且不能重复");
                cleanStops.add(Map.of("spot_id",spot,"travel_minutes",minutes(row,"travel_minutes",360),"rest_minutes",minutes(row,"rest_minutes",360)));
            }
            long revision=number(body.get("revision"));String data=db.encode(Map.of("days",cleanDays,"stops",cleanStops));
            if(revision==0){
                if(db.update("INSERT OR IGNORE INTO trip_day_schedules(trip_id,settings) VALUES(?,?)",id,data)==0)throw new ApiError(409,"每日时间已在其他窗口更新，请刷新后再修改");
            }else if(db.update("UPDATE trip_day_schedules SET settings=?,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE trip_id=? AND revision=?",data,id,revision)==0)throw new ApiError(409,"每日时间已在其他窗口更新，请刷新后再修改");
            TripVersions.capture(db,id,"TIME",0);
            return view(id,t);
        });
    }
}
