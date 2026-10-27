package cn.edu.travel;

import java.util.*;

/**
 * 有界的多日时段分配：必去景点先作回溯可行性搜索，再以束搜索扩充可选景点。
 * 交通时间是用户给每站填写的到达前估计，不推断真实道路、开放时间或全局最优路线。
 * 此类没有数据库、网络、当前时间或可变静态状态，同一输入总是产生同一结果。
 */
public final class ConstraintPlanner {
    private static final int HARD_NODE_LIMIT=50_000, SEED_LIMIT=64, BEAM_WIDTH=256;
    private ConstraintPlanner(){}

    private record Candidate(int index,long id,String title,String cityName,String image,String theme,
                             int visit,int travel,int rest,int priority,boolean required,int fixedDay,int utility) {
        int cost(){return visit+travel+rest;}
    }
    private record Window(int day,int start,int end){int capacity(){return end-start;}}
    private static final class State {
        final int[] assignments,loads;final int utility,matches,count;
        State(int candidates,int days){assignments=new int[candidates];loads=new int[days];utility=matches=count=0;}
        State(State old,Candidate c,int day,boolean match){
            assignments=old.assignments.clone();loads=old.loads.clone();assignments[c.index]=day+1;loads[day]+=c.cost();
            utility=old.utility+c.utility;matches=old.matches+(match?1:0);count=old.count+1;
        }
    }
    private static final class Search {
        final List<Candidate> mandatory;final List<Window> windows;final String theme;
        final List<State> seeds=new ArrayList<>();int hardNodes,beamNodes;boolean hardLimited,seedLimited,beamLimited;
        Search(List<Candidate> mandatory,List<Window>windows,String theme){this.mandatory=mandatory;this.windows=windows;this.theme=theme;}
        void required(int at,State state){
            if(hardNodes>=HARD_NODE_LIMIT){hardLimited=true;return;}
            hardNodes++;
            if(at==mandatory.size()){
                seeds.add(state);if(seeds.size()>=SEED_LIMIT)seedLimited=true;return;
            }
            Candidate c=mandatory.get(at);Set<Integer> symmetricFree=new HashSet<>();
            for(int d=0;d<windows.size();d++){
                if(c.fixedDay>0&&c.fixedDay!=d+1)continue;
                int free=windows.get(d).capacity()-state.loads[d];
                if(c.cost()>free)continue;
                // 固定日期先分配，后续无固定日期时等剩余容量的日期在可行性上对称。
                if(c.fixedDay==0&&!symmetricFree.add(free))continue;
                required(at+1,new State(state,c,d,!theme.isEmpty()&&theme.equals(c.theme)));
                if(hardLimited||seedLimited)return;
            }
        }
    }

    public static Map<String,Object> solve(Map<String,Object> config,List<Map<String,Object>> spots){
        try{return run(config,spots);}catch(IllegalArgumentException|NullPointerException e){
            return failure("infeasible",List.of(conflict("INVALID_INPUT","规划条件无法读取："+Objects.toString(e.getMessage(),"数据不完整"),"请重新检查日期、时段和候选景点。")),new Search(List.of(),List.of(),""));
        }
    }
    private static Map<String,Object> run(Map<String,Object> config,List<Map<String,Object>> spots){
        int days=integer(config.get("days"));if(days<1||days>7)throw new IllegalArgumentException("天数应为 1～7 天");
        String theme=str(config.get("theme")),objective=str(config.getOrDefault("objective","balanced"));
        if(!Set.of("balanced","interest","compact").contains(objective))throw new IllegalArgumentException("排序偏好无效");
        List<Map<String,Object>> rawWindows=rows(config.get("day_windows"));if(rawWindows.size()!=days)throw new IllegalArgumentException("每天都需要一个完整时段");
        Window[] orderedWindows=new Window[days];
        for(var w:rawWindows){int day=integer(w.get("day_number")),start=clock(w.get("start_time")),end=clock(w.get("end_time"));
            if(day<1||day>days||orderedWindows[day-1]!=null||end<=start)throw new IllegalArgumentException("每日时段有重复或起止时间无效");
            orderedWindows[day-1]=new Window(day,start,end);
        }
        List<Window> windows=Arrays.asList(orderedWindows);Map<Long,Map<String,Object>> sources=new HashMap<>();
        for(var s:spots)sources.put(number(s.containsKey("id")?s.get("id"):s.get("spot_id")),s);
        List<Map<String,Object>> selected=rows(config.get("candidates"));if(selected.isEmpty()||selected.size()>18)throw new IllegalArgumentException("候选景点应为 1～18 个");
        List<Candidate> candidates=new ArrayList<>();Set<Long> ids=new HashSet<>();long city=number(config.get("city_id"));
        for(var c:selected){long id=number(c.get("spot_id"));var spot=sources.get(id);
            if(spot==null||id<1||!ids.add(id)||number(spot.get("city_id"))!=city)throw new IllegalArgumentException("候选景点不存在、重复或不属于当前城市");
            int priority=integer(c.get("priority")),fixed=integer(c.getOrDefault("fixed_day",0)),travel=integer(c.getOrDefault("travel_minutes",0)),rest=integer(c.getOrDefault("rest_minutes",0)),visit=integer(spot.get("duration_minutes"));
            if(priority<1||priority>5||fixed<0||fixed>days||travel<0||travel>240||rest<0||rest>180||visit<1||visit>20160)throw new IllegalArgumentException("景点的日期、时长或优先级超出范围");
            if(!(c.get("required")instanceof Boolean))throw new IllegalArgumentException("必去选项必须是布尔值");
            String spotTheme=str(spot.get("theme"));boolean match=!theme.isEmpty()&&theme.equals(spotTheme);
            candidates.add(new Candidate(candidates.size(),id,str(spot.get("title")),str(spot.get("city_name")),str(spot.get("image")),spotTheme,visit,travel,rest,priority,Boolean.TRUE.equals(c.get("required"))||fixed>0,fixed,priority*20+(match?15:0)));
        }
        List<Candidate> mandatory=candidates.stream().filter(c->c.required).sorted(Comparator.<Candidate>comparingInt(c->c.fixedDay>0?0:1).thenComparing(Comparator.comparingInt(Candidate::cost).reversed()).thenComparingLong(c->c.id)).toList();
        Search search=new Search(mandatory,windows,theme);List<Map<String,String>> problems=preflight(candidates,windows);
        if(!problems.isEmpty())return failure("infeasible",problems,search);
        if(mandatory.isEmpty())search.seeds.add(new State(candidates.size(),days));else search.required(0,new State(candidates.size(),days));
        if(search.seeds.isEmpty()){
            if(search.hardLimited)return failure("search_limited",List.of(conflict("SEARCH_LIMIT","达到必去景点可行性搜索的节点上限，尚未找到完整方案。","这不代表条件一定无解；可减少候选、放宽固定日期或增加每天可用时间后重试。")),search);
            return failure("infeasible",List.of(conflict("REQUIRED_CANNOT_PACK","必去景点虽然可能分别放入，但其整段时长无法同时装入目前各天的时段。","请增加某天的可用时段，或调整固定日期、必去选项以及交通休息估计。")),search);
        }
        Comparator<State> ranking=ranking(objective,windows);List<State> beam=new ArrayList<>(search.seeds);beam.sort(ranking);
        List<Candidate> optional=candidates.stream().filter(c->!c.required).sorted(Comparator.comparingInt((Candidate c)->c.utility).reversed().thenComparingInt(Candidate::cost).thenComparingLong(c->c.id)).toList();
        for(Candidate c:optional){Map<String,State> next=new HashMap<>();
            for(State s:beam){next.put(key(s),s);for(int d=0;d<days;d++)if(s.loads[d]+c.cost()<=windows.get(d).capacity()){
                State placed=new State(s,c,d,!theme.isEmpty()&&theme.equals(c.theme));search.beamNodes++;next.putIfAbsent(key(placed),placed);
            }}
            beam=new ArrayList<>(next.values());beam.sort(ranking);if(beam.size()>BEAM_WIDTH){search.beamLimited=true;beam=new ArrayList<>(beam.subList(0,BEAM_WIDTH));}
        }
        beam.removeIf(s->s.count==0);beam.sort(ranking);
        if(beam.isEmpty())return failure("infeasible",List.of(conflict("NO_VISIT_FITS","当前候选景点的游览、交通和休息合计均无法放入任一天的可用时段。","请延长每天的可用时间，或调整交通休息估计、重新选择景点。")),search);
        List<Map<String,Object>> plans=new ArrayList<>();Set<String> signatures=new HashSet<>();
        for(State s:beam)if(signatures.add(key(s))){plans.add(plan(plans.size(),s,candidates,windows,theme,objective));if(plans.size()==3)break;}
        State best=beam.get(0);List<Map<String,Object>> omitted=new ArrayList<>();
        for(Candidate c:candidates)if(best.assignments[c.index]==0){boolean fits=windows.stream().anyMatch(w->(c.fixedDay==0||c.fixedDay==w.day)&&c.cost()<=w.capacity());
            omitted.add(Map.of("spot_id",c.id,"title",c.title,"reason",fits?"当前方案依据优先级、主题和每日余量作出取舍；可以提高优先级或延长时段再规划。":"游览、交通和休息合计 "+c.cost()+" 分钟，超过允许日期的可用时段。"));
        }
        var out=new LinkedHashMap<String,Object>();out.put("status","feasible");out.put("plans",plans);out.put("conflicts",List.of());out.put("search",metadata(search));out.put("unscheduled",omitted);return out;
    }
    private static List<Map<String,String>> preflight(List<Candidate> candidates,List<Window>windows){
        List<Map<String,String>> conflicts=new ArrayList<>();int requiredTotal=0;int[] fixed=new int[windows.size()];
        for(Candidate c:candidates)if(c.required){requiredTotal+=c.cost();if(c.fixedDay>0)fixed[c.fixedDay-1]+=c.cost();
            int max=windows.stream().filter(w->c.fixedDay==0||c.fixedDay==w.day).mapToInt(Window::capacity).max().orElse(0);
            if(c.cost()>max)conflicts.add(conflict("REQUIRED_TOO_LONG",c.title+"为必去景点，游览、交通及休息共 "+c.cost()+" 分钟，允许日期最多只有 "+max+" 分钟。","延长该景点可安排日期的时段，或调整交通休息估计、固定日期和必去选项。"));
        }
        for(int d=0;d<fixed.length;d++)if(fixed[d]>windows.get(d).capacity())conflicts.add(conflict("FIXED_DAY_OVERFLOW","固定在第 "+(d+1)+" 天的必去安排共 "+fixed[d]+" 分钟，超过当天的 "+windows.get(d).capacity()+" 分钟。","将部分景点固定到其他日期，或延长第 "+(d+1)+" 天的时段。"));
        int capacity=windows.stream().mapToInt(Window::capacity).sum();if(requiredTotal>capacity)conflicts.add(conflict("REQUIRED_TOTAL_OVERFLOW","必去安排合计 "+requiredTotal+" 分钟，超过全部日期的 "+capacity+" 分钟。","增加天数或可用时段，或减少必去景点、调整交通休息估计。"));return conflicts;
    }
    private static Comparator<State> ranking(String objective,List<Window>windows){return (a,b)->{int n=Double.compare(score(b,objective,windows),score(a,objective,windows));if(n!=0)return n;n=Integer.compare(b.utility,a.utility);if(n!=0)return n;n=Integer.compare(b.count,a.count);if(n!=0)return n;for(int i=0;i<a.assignments.length;i++){n=Integer.compare(a.assignments[i],b.assignments[i]);if(n!=0)return n;}return 0;};}
    private static double gap(State s,List<Window>windows){double low=1,high=0;for(int d=0;d<windows.size();d++){double rate=(double)s.loads[d]/windows.get(d).capacity();low=Math.min(low,rate);high=Math.max(high,rate);}return (high-low)*100;}
    private static int active(State s){int n=0;for(int load:s.loads)if(load>0)n++;return n;}
    private static int total(State s){return Arrays.stream(s.loads).sum();}
    private static double score(State s,String objective,List<Window>windows){return switch(objective){case "interest"->s.utility*100.0+s.matches*40-total(s)/60.0;case "compact"->s.utility*100.0-active(s)*80-total(s)/60.0;default->s.utility*100.0-gap(s,windows)*8+s.count*2;};}
    private static Map<String,Object> plan(int index,State s,List<Candidate> candidates,List<Window>windows,String theme,String objective){
        List<Map<String,Object>> items=new ArrayList<>(),days=new ArrayList<>();
        for(Window w:windows){int cursor=w.start,visit=0,travel=0,rest=0,order=0;List<Map<String,Object>> today=new ArrayList<>();
            for(Candidate c:candidates)if(s.assignments[c.index]==w.day){cursor+=c.travel;String arrival=clock(cursor);cursor+=c.visit;String departure=clock(cursor);cursor+=c.rest;visit+=c.visit;travel+=c.travel;rest+=c.rest;
                String reason=c.fixedDay>0?"按你的要求固定在第 "+c.fixedDay+" 天。":c.required?"满足你标记的必去要求。":"根据优先级与当天余量选入。";
                if(!theme.isEmpty()&&theme.equals(c.theme))reason+="符合你选择的主题。";reason+="优先级 "+c.priority+"，计入交通 "+c.travel+" 分钟和休息 "+c.rest+" 分钟。";
                var item=new LinkedHashMap<String,Object>();item.put("spot_id",c.id);item.put("day_number",w.day);item.put("order",order++);item.put("title",c.title);item.put("city_name",c.cityName);item.put("image",c.image);item.put("duration_minutes",c.visit);item.put("travel_minutes",c.travel);item.put("rest_minutes",c.rest);item.put("arrival",arrival);item.put("departure",departure);item.put("reason",reason);items.add(item);today.add(item);
            }
            var day=new LinkedHashMap<String,Object>();day.put("day_number",w.day);day.put("start_time",clock(w.start));day.put("end_time",clock(w.end));day.put("finish_time",clock(cursor));day.put("visit_minutes",visit);day.put("travel_minutes",travel);day.put("rest_minutes",rest);day.put("used_minutes",cursor-w.start);day.put("remaining_minutes",w.end-cursor);day.put("items",today);days.add(day);
        }
        String mode=switch(objective){case "interest"->"优先兴趣";case "compact"->"集中安排";default->"均衡安排";};
        long required=candidates.stream().filter(c->c.required).count(),fixed=candidates.stream().filter(c->c.fixedDay>0).count();
        List<String> explanations=new ArrayList<>();explanations.add("满足 "+required+" 个必去景点及 "+fixed+" 项固定日期要求，所有到访均在每天的起止时段内完成。");
        explanations.add(switch(objective){case "interest"->"排序优先考虑你填写的优先级及主题匹配。";case "compact"->"在兴趣得分基础上，倾向使用更少的活动日期，留出整段自由时间。";default->"在兴趣得分基础上，倾向让每天的时段利用率更接近。";});
        explanations.add("同一天按候选清单顺序展示；每站都计入到达前交通和游览后休息，包括第一站及最后一站。");
        explanations.add("游览时长来自编辑建议，交通休息来自你的输入；这是一份可解释的候选安排，不代表实时道路或景区开放情况。");
        var metrics=new LinkedHashMap<String,Object>();metrics.put("utility",s.utility);metrics.put("theme_matches",s.matches);metrics.put("active_days",active(s));metrics.put("total_minutes",total(s));metrics.put("balance_gap",Math.round(gap(s,windows)*100)/100.0);metrics.put("score",Math.round(score(s,objective,windows)*100)/100.0);
        return Map.of("index",index,"title","方案 "+(index+1)+" · "+mode,"items",items,"days",days,"metrics",metrics,"explanations",explanations);
    }
    private static Map<String,Object> metadata(Search s){return Map.of("algorithm","必去回溯 + 可选景点束搜索","nodes",s.hardNodes+s.beamNodes,"hard_nodes",s.hardNodes,"hard_node_limit",HARD_NODE_LIMIT,"beam_width",BEAM_WIDTH,"limited",s.hardLimited||s.seedLimited||s.beamLimited,"optimal",false);}
    private static Map<String,String> conflict(String code,String message,String suggestion){return Map.of("code",code,"message",message,"suggestion",suggestion);}
    private static Map<String,Object> failure(String status,List<Map<String,String>> problems,Search search){return Map.of("status",status,"plans",List.of(),"conflicts",problems,"search",metadata(search),"unscheduled",List.of());}
    private static String key(State s){return Arrays.toString(s.assignments);}
    private static String str(Object raw){return Objects.toString(raw,"");}
    private static long number(Object raw){try{if(raw==null)throw new NumberFormatException();return Long.parseLong(raw.toString());}catch(NumberFormatException e){throw new IllegalArgumentException("数字格式无效");}}
    private static int integer(Object raw){long n=number(raw);if(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw new IllegalArgumentException("数字超出范围");return(int)n;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object raw){if(!(raw instanceof List<?> list))throw new IllegalArgumentException("列表格式无效");List<Map<String,Object>>out=new ArrayList<>();for(Object item:list){if(!(item instanceof Map<?,?>))throw new IllegalArgumentException("列表条目格式无效");out.add((Map<String,Object>)item);}return out;}
    private static int clock(Object value){String s=str(value);if(!s.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw new IllegalArgumentException("时间格式无效");return Integer.parseInt(s.substring(0,2))*60+Integer.parseInt(s.substring(3));}
    private static String clock(int minutes){return String.format(Locale.ROOT,"%02d:%02d",minutes/60,minutes%60);}
}
