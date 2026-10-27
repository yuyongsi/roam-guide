package cn.edu.travel;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import static cn.edu.travel.ApiController.*;

/** 在现有景点中调整推荐；保留用户锁定的日期与站点，不请求外部地图。 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationDepthController {
    final Database db;
    public RecommendationDepthController(Database db){this.db=db;}
    @PostMapping("/adjust") Object adjust(@RequestBody Map<String,Object> b){
        long city=number(b.get("city"));int days=(int)number(b.get("days"));
        String theme=Objects.toString(b.get("theme"),""),pace=Objects.toString(b.get("pace"),"relaxed"),mode=Objects.toString(b.get("mode"),"recombine");
        if(days<1||days>7||!Set.of("relaxed","normal","full").contains(pace)||!Set.of("","人文历史","山水风景","街巷生活").contains(theme)||!Set.of("replace","recombine").contains(mode))throw new ApiError(400,"推荐条件不正确");
        var c=db.one("SELECT id,name FROM cities WHERE id=? AND published=1",city);int limit=pace.equals("relaxed")?240:pace.equals("normal")?360:480,max=pace.equals("relaxed")?2:3;
        var rows=db.list("SELECT s.id AS spot_id,s.title,s.theme,s.image,s.duration_minutes,c.name AS city_name FROM spots s JOIN cities c ON c.id=s.city_id WHERE s.city_id=? AND s.published=1 AND c.published=1 ORDER BY CASE WHEN s.theme=? THEN 0 ELSE 1 END,s.duration_minutes,s.id",city,theme);
        Map<Long,Map<String,Object>> byId=new LinkedHashMap<>();for(var s:rows)byId.put(number(s.get("spot_id")),s);
        var current=items(b.get("items"),byId,days);Set<Long> locked=ids(b.get("locked_ids")),excluded=ids(b.get("exclude_ids"));
        Set<Long> present=new HashSet<>();for(var s:current)present.add(number(s.get("spot_id")));
        if(!present.containsAll(locked)||!Collections.disjoint(locked,excluded))throw new ApiError(400,"保留的景点必须在当前安排中，且不能同时排除");
        List<Map<String,Object>> picked=new ArrayList<>();
        if(mode.equals("replace")){
            long target=number(b.get("replace_id"));if(!present.contains(target)||locked.contains(target))throw new ApiError(400,"请先解除这一站的保留，再进行替换");
            var old=current.stream().filter(s->number(s.get("spot_id"))==target).findFirst().orElseThrow();int day=(int)number(old.get("day_number"));
            long load=current.stream().filter(s->number(s.get("day_number"))==day&&number(s.get("spot_id"))!=target).mapToLong(s->number(s.get("duration_minutes"))).sum();
            Map<String,Object> alternative=null;
            for(var s:rows)if(!present.contains(number(s.get("spot_id")))&&!excluded.contains(number(s.get("spot_id")))&&(load==0||load+number(s.get("duration_minutes"))<=limit)){alternative=new LinkedHashMap<>(s);break;}
            if(alternative==null)throw new ApiError(409,"当前条件下没有其他合适景点，可以调整主题、节奏或目的地后再试");
            alternative.put("day_number",day);alternative.put("reason",reason(alternative,theme,limit,load)+" 已替换原来的景点，其余安排保持原日期。");
            for(var s:current)picked.add(number(s.get("spot_id"))==target?alternative:s);
        }else{
            long[] loads=new long[days];int[] counts=new int[days];Set<Long> used=new HashSet<>();
            for(var s:current)if(locked.contains(number(s.get("spot_id")))){var keep=new LinkedHashMap<>(s);int day=(int)number(s.get("day_number"));loads[day-1]+=number(s.get("duration_minutes"));counts[day-1]++;used.add(number(s.get("spot_id")));keep.put("reason","已保留你选定的景点和第 "+day+" 天安排。"+(loads[day-1]>limit?"当天建议时长超过当前节奏目标，请自行留意。":""));picked.add(keep);}
            int rotation=(int)Math.max(0,Math.min(1000,number(b.getOrDefault("rotation",0))));
            List<Map<String,Object>> ordered=new ArrayList<>(rows);
            if(!ordered.isEmpty()){Collections.rotate(ordered,-(rotation%ordered.size()));ordered.sort(Comparator.comparingInt(s->!theme.isEmpty()&&theme.equals(s.get("theme"))?0:1));}
            for(var s:ordered){long sid=number(s.get("spot_id"));if(used.contains(sid)||excluded.contains(sid))continue;long minutes=number(s.get("duration_minutes"));int best=-1;
                for(int d=0;d<days;d++)if(counts[d]<max&&(loads[d]+minutes<=limit||counts[d]==0)&&(best<0||loads[d]<loads[best]))best=d;
                if(best<0)continue;var next=new LinkedHashMap<>(s);next.put("day_number",best+1);next.put("reason",reason(s,theme,limit,loads[best]));picked.add(next);used.add(sid);loads[best]+=minutes;counts[best]++;
            }
            picked.sort(Comparator.comparingLong(s->number(s.get("day_number"))));
        }
        return Map.of("city",c,"days",days,"theme",theme,"pace",pace,"items",picked,"available_count",rows.size(),"target_minutes_per_day",limit);
    }
    String reason(Map<String,Object>s,String theme,int limit,long load){long m=number(s.get("duration_minutes"));return (!theme.isEmpty()&&theme.equals(s.get("theme"))?"符合你选择的主题；":"同城补充选择；")+"建议停留 "+m+" 分钟"+(load+m>limit?"，这一站超过当前节奏的目标，请按实际情况调整。":"，按当前游玩节奏安排。");}
    Set<Long> ids(Object raw){if(raw==null)return new HashSet<>();if(!(raw instanceof List<?> list)||list.size()>80)throw new ApiError(400,"景点选择格式不正确");Set<Long> out=new HashSet<>();for(Object o:list){long id=number(o);if(id<=0)throw new ApiError(400,"景点编号不正确");out.add(id);}return out;}
    List<Map<String,Object>> items(Object raw,Map<Long,Map<String,Object>> byId,int days){if(!(raw instanceof List<?> list)||list.size()>80)throw new ApiError(400,"当前安排格式不正确");List<Map<String,Object>> out=new ArrayList<>();Set<Long> seen=new HashSet<>();for(Object value:list){if(!(value instanceof Map<?,?> s))throw new ApiError(400,"当前安排格式不正确");long id=number(s.get("spot_id")),day=number(s.get("day_number"));if(day<1||day>days||!seen.add(id)||!byId.containsKey(id))throw new ApiError(400,"当前景点已变化，请重新生成推荐");var next=new LinkedHashMap<>(byId.get(id));next.put("day_number",day);next.put("reason","保留当前安排，建议停留 "+next.get("duration_minutes")+" 分钟。");out.add(next);}return out;}
}
