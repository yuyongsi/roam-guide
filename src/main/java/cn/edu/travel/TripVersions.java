package cn.edu.travel;

import java.util.*;
import static cn.edu.travel.ApiController.*;

/** 不可变的私人行程快照；所有 capture 调用与原写操作共用数据库事务。 */
public final class TripVersions {
    private TripVersions(){}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object raw){return raw instanceof Map<?,?>?(Map<String,Object>)raw:Map.of();}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object raw){if(!(raw instanceof List<?> list))return List.of();return list.stream().filter(x->x instanceof Map<?,?>).map(x->(Map<String,Object>)x).toList();}
    static long n(Object raw){return number(raw==null?0:raw);}
    static String text(Object raw){return Objects.toString(raw,"");}
    static Map<String,Object> read(Database db,Object raw){var s=map(db.decode(raw));if(!s.containsKey("items")||!s.containsKey("schedule"))throw new ApiError(409,"这个版本的数据无法完整读取，请选择其他版本");return s;}
    public static long head(Database db,long id){return db.count("SELECT coalesce(max(version_number),0) FROM trip_versions WHERE trip_id=?",id);}
    public static long scheduleRevision(Database db,long id){return db.count("SELECT coalesce(max(revision),0) FROM trip_day_schedules WHERE trip_id=?",id);}
    public static void bootstrap(Database db){db.transaction(status->{for(var t:db.list("SELECT id FROM trips WHERE NOT EXISTS(SELECT 1 FROM trip_versions v WHERE v.trip_id=trips.id)"))capture(db,n(t.get("id")),"BASELINE",0);return null;});}
    public static void ensure(Database db,long id){if(head(db,id)==0)capture(db,id,"BASELINE",0);}
    static Map<String,Object> snapshot(Database db,long id){
        var t=db.one("SELECT title,days,start_date,notes,checklist FROM trips WHERE id=?",id);var s=new LinkedHashMap<String,Object>();
        for(String key:List.of("title","days","start_date","notes"))s.put(key,t.get(key));s.put("checklist",db.decode(t.get("checklist")));
        var items=db.list("SELECT i.spot_id,i.day_number,i.note,s.title,s.image,s.published,s.duration_minutes,c.name AS city_name FROM trip_items i JOIN spots s ON s.id=i.spot_id JOIN cities c ON c.id=s.city_id WHERE i.trip_id=? ORDER BY i.day_number,i.sort_order,i.id",id);s.put("items",items);
        var stored=db.list("SELECT settings FROM trip_day_schedules WHERE trip_id=?",id);var conf=stored.isEmpty()?Map.<String,Object>of():map(db.decode(stored.get(0).get("settings")));
        Map<Long,Map<String,Object>> starts=index(rows(conf.get("days")),"day_number"),stops=index(rows(conf.get("stops")),"spot_id");
        List<Map<String,Object>> dayRows=new ArrayList<>(),stopRows=new ArrayList<>();
        for(long d=1;d<=n(t.get("days"));d++)dayRows.add(Map.of("day_number",d,"start_time",starts.getOrDefault(d,Map.of()).getOrDefault("start_time","09:00")));
        for(var item:items){long sid=n(item.get("spot_id"));var p=stops.getOrDefault(sid,Map.of());stopRows.add(Map.of("spot_id",sid,"travel_minutes",n(p.get("travel_minutes")),"rest_minutes",n(p.get("rest_minutes"))));}
        s.put("schedule",Map.of("days",dayRows,"stops",stopRows));return s;
    }
    static Object normalize(Object value){if(value instanceof Number x)return x.longValue();if(value instanceof Map<?,?> m){var out=new TreeMap<String,Object>();m.forEach((k,v)->out.put(String.valueOf(k),normalize(v)));return out;}if(value instanceof List<?> list)return list.stream().map(TripVersions::normalize).toList();return value;}
    static Object meaning(Map<String,Object>s){var out=new LinkedHashMap<String,Object>();for(String key:List.of("title","days","start_date","notes","checklist","schedule"))out.put(key,s.get(key));out.put("items",rows(s.get("items")).stream().map(i->Map.of("spot_id",i.get("spot_id"),"day_number",i.get("day_number"),"note",text(i.get("note")))).toList());return normalize(out);}
    public static long capture(Database db,long id,String kind,long restoredFrom){
        var snapshot=snapshot(db,id);var previous=db.list("SELECT version_number,snapshot FROM trip_versions WHERE trip_id=? ORDER BY version_number DESC LIMIT 1",id);
        long version=previous.isEmpty()?1:n(previous.get(0).get("version_number"))+1;List<Map<String,String>> changes=List.of();
        if(!previous.isEmpty()){var before=read(db,previous.get(0).get("snapshot"));if(!kind.equals("RESTORE")&&Objects.equals(meaning(before),meaning(snapshot)))return version-1;changes=diff(before,snapshot);}
        String summary=kind.equals("BASELINE")?"开始记录时的安排":kind.equals("CREATE")?"创建行程":kind.equals("RESTORE")?"从版本 "+restoredFrom+" 恢复安排":summary(changes);
        long revision=db.count("SELECT revision FROM trips WHERE id=?",id);
        db.update("INSERT INTO trip_versions(trip_id,version_number,trip_revision,schedule_revision,kind,restored_from,summary,snapshot) VALUES(?,?,?,?,?,?,?,?)",id,version,revision,scheduleRevision(db,id),kind,restoredFrom,summary,db.encode(snapshot));return version;
    }
    static String summary(List<Map<String,String>> changes){var counts=new LinkedHashMap<String,Integer>();for(var c:changes){String name=switch(c.get("type")){case "added"->"新增景点";case "removed"->"移除景点";case "moved"->"调整日期或顺序";case "note"->"修改景点备注";case "checklist"->"调整清单";case "time"->"调整时间";default->"修改基本资料";};counts.merge(name,1,Integer::sum);}return counts.isEmpty()?"保存安排":String.join(" · ",counts.entrySet().stream().map(e->e.getKey()+" "+e.getValue()+" 项").toList());}
    static Map<Long,Map<String,Object>> index(List<Map<String,Object>> rows,String key){var out=new LinkedHashMap<Long,Map<String,Object>>();for(var r:rows)out.put(n(r.get(key)),r);return out;}
    static Map<Long,String> positions(List<Map<String,Object>> rows){var out=new HashMap<Long,String>();var counts=new HashMap<Long,Integer>();for(var r:rows){long day=n(r.get("day_number"));int position=counts.merge(day,1,Integer::sum);out.put(n(r.get("spot_id")),"第 "+day+" 天，第 "+position+" 站");}return out;}
    static void change(List<Map<String,String>> out,String type,String label,Object before,Object after){String a=text(before),b=text(after);if(!a.equals(b))out.add(Map.of("type",type,"label",label,"before",a,"after",b));}
    static String task(Map<String,Object> t){if(t==null)return "";return text(t.get("text"))+ (Boolean.TRUE.equals(t.get("done"))?"（已完成）":"（待完成）");}
    static String stopTime(Map<String,Object> t){return "交通 "+n(t.get("travel_minutes"))+" 分钟，休息 "+n(t.get("rest_minutes"))+" 分钟";}
    public static List<Map<String,String>> diff(Map<String,Object> before,Map<String,Object> after){
        List<Map<String,String>> out=new ArrayList<>();String[][] fields={{"title","行程名称"},{"days","行程天数"},{"start_date","出发日期"},{"notes","行程备注"}};
        for(var f:fields)change(out,"field",f[1],before.get(f[0]),after.get(f[0]));
        var oldRows=rows(before.get("items"));var newRows=rows(after.get("items"));var a=index(oldRows,"spot_id");var b=index(newRows,"spot_id");var oldPos=positions(oldRows);var newPos=positions(newRows);
        for(var e:a.entrySet())if(!b.containsKey(e.getKey()))change(out,"removed",text(e.getValue().get("title")),oldPos.get(e.getKey()),"");
        for(var e:b.entrySet()){long id=e.getKey();var next=e.getValue();String title=text(next.get("title"));if(!a.containsKey(id)){change(out,"added",title,"",newPos.get(id));if(!text(next.get("note")).isEmpty())change(out,"note",title+"的备注","",next.get("note"));}else{change(out,"moved",title,oldPos.get(id),newPos.get(id));change(out,"note",title+"的备注",a.get(id).get("note"),next.get("note"));}}
        Map<String,Map<String,Object>> oldTasks=new LinkedHashMap<>(),newTasks=new LinkedHashMap<>();for(var t:rows(before.get("checklist")))oldTasks.put(text(t.get("key")),t);for(var t:rows(after.get("checklist")))newTasks.put(text(t.get("key")),t);
        for(var e:oldTasks.entrySet())if(!newTasks.containsKey(e.getKey()))change(out,"checklist","移除行前事项",task(e.getValue()),"");
        for(var e:newTasks.entrySet())change(out,"checklist",oldTasks.containsKey(e.getKey())?"修改行前事项":"新增行前事项",task(oldTasks.get(e.getKey())),task(e.getValue()));
        if(oldTasks.keySet().equals(newTasks.keySet())&&!new ArrayList<>(oldTasks.keySet()).equals(new ArrayList<>(newTasks.keySet())))change(out,"checklist","清单顺序",String.join("、",oldTasks.values().stream().map(t->text(t.get("text"))).toList()),String.join("、",newTasks.values().stream().map(t->text(t.get("text"))).toList()));
        var oldSchedule=map(before.get("schedule"));var newSchedule=map(after.get("schedule"));var oldDays=index(rows(oldSchedule.get("days")),"day_number");var newDays=index(rows(newSchedule.get("days")),"day_number");
        var dayIds=new TreeSet<Long>(oldDays.keySet());dayIds.addAll(newDays.keySet());for(long day:dayIds)change(out,"time","第 "+day+" 天出发时间",oldDays.containsKey(day)?oldDays.get(day).get("start_time"):"未安排",newDays.containsKey(day)?newDays.get(day).get("start_time"):"未安排");
        var oldTimes=index(rows(oldSchedule.get("stops")),"spot_id");var newTimes=index(rows(newSchedule.get("stops")),"spot_id");
        for(var e:newTimes.entrySet()){var old=oldTimes.getOrDefault(e.getKey(),Map.of());if(!old.isEmpty()||n(e.getValue().get("travel_minutes"))+n(e.getValue().get("rest_minutes"))>0)change(out,"time",text(b.getOrDefault(e.getKey(),Map.of()).get("title"))+"的交通与休息",stopTime(old),stopTime(e.getValue()));}
        return out;
    }
}
