import java.util.*;
import cn.edu.travel.ConstraintPlanner;

/** Directly exercises the production solver; small mandatory cases are checked against exhaustive search. */
public class verify_constraint_core {
 static int checks=0;static final Random random=new Random(240625);
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 static Map<String,Object> spot(long id,int minutes,String theme){return Map.of("id",id,"city_id",1L,"city_name","示例城市","title","景点"+id,"image","/assets/nanyang.jpg","theme",theme,"duration_minutes",minutes);}
 static Map<String,Object> choice(long id,boolean required,int fixed,int priority,int travel,int rest){return Map.of("spot_id",id,"required",required,"fixed_day",fixed,"priority",priority,"travel_minutes",travel,"rest_minutes",rest);}
 static Map<String,Object> config(int[] capacity,List<Map<String,Object>>choices,String objective){List<Map<String,Object>>days=new ArrayList<>();for(int i=0;i<capacity.length;i++)days.add(Map.of("day_number",i+1,"start_time","08:00","end_time",clock(480+capacity[i])));return Map.of("city_id",1L,"days",capacity.length,"theme","人文历史","objective",objective,"day_windows",days,"candidates",choices);}
 static String clock(int n){return String.format(Locale.ROOT,"%02d:%02d",n/60,n%60);}
 static int n(Object raw){return ((Number)raw).intValue();}
 @SuppressWarnings("unchecked")static List<Map<String,Object>> list(Object raw){return (List<Map<String,Object>>)raw;}
 @SuppressWarnings("unchecked")static Map<String,Object> map(Object raw){return (Map<String,Object>)raw;}
 static boolean hasCode(Map<String,Object>r,String code){return list(r.get("conflicts")).stream().anyMatch(x->code.equals(x.get("code")));}
 static boolean brute(int at,List<Map<String,Object>>mandatory,Map<Long,Integer>costs,int[]remaining){if(at==mandatory.size())return true;var c=mandatory.get(at);int fixed=n(c.get("fixed_day")),cost=costs.get(((Number)c.get("spot_id")).longValue());for(int d=0;d<remaining.length;d++){if(fixed>0&&fixed!=d+1||remaining[d]<cost)continue;remaining[d]-=cost;if(brute(at+1,mandatory,costs,remaining))return true;remaining[d]+=cost;}return false;}
 static void valid(Map<String,Object>r,Map<String,Object>cfg,List<Map<String,Object>>spots){
  int days=n(cfg.get("days"));Map<Long,Map<String,Object>> choices=new HashMap<>(),source=new HashMap<>();for(var c:list(cfg.get("candidates")))choices.put(((Number)c.get("spot_id")).longValue(),c);for(var s:spots)source.put(((Number)s.get("id")).longValue(),s);
  check(!list(r.get("plans")).isEmpty()&&list(r.get("plans")).size()<=3,"one to three nonempty plans");Set<String> fingerprints=new HashSet<>();
  for(var p:list(r.get("plans"))){Set<Long> seen=new HashSet<>();int[]sum=new int[days];for(var i:list(p.get("items"))){long id=((Number)i.get("spot_id")).longValue();int d=n(i.get("day_number"));check(seen.add(id)&&choices.containsKey(id),"unique chosen attraction");check(d>=1&&d<=days,"day in range");var c=choices.get(id);check(n(c.get("fixed_day"))==0||n(c.get("fixed_day"))==d,"fixed day honored");int cost=n(source.get(id).get("duration_minutes"))+n(c.get("travel_minutes"))+n(c.get("rest_minutes"));sum[d-1]+=cost;}
   for(var e:choices.entrySet())if(Boolean.TRUE.equals(e.getValue().get("required"))||n(e.getValue().get("fixed_day"))>0)check(seen.contains(e.getKey()),"every required attraction included");
   var dayRows=list(p.get("days"));check(dayRows.size()==days,"all days returned");for(int d=0;d<days;d++){var day=dayRows.get(d);check(n(day.get("used_minutes"))==sum[d],"daily cost equals visits plus all travel and rest");check(n(day.get("remaining_minutes"))>=0,"deadline respected");check(n(day.get("used_minutes"))==n(day.get("visit_minutes"))+n(day.get("travel_minutes"))+n(day.get("rest_minutes")),"daily components sum");}
   check(fingerprints.add(p.get("items").toString()),"distinct proposals");check(!Boolean.TRUE.equals(map(r.get("search")).get("optimal")),"no unjustified optimal claim");
  }
 }
 public static void main(String[]args){
  var s=List.of(spot(1,45,"人文历史"));var c=List.of(choice(1,true,0,3,10,10));check(hasCode(ConstraintPlanner.solve(config(new int[]{60},c,"balanced"),s),"REQUIRED_TOO_LONG"),"single required too long");
  s=List.of(spot(1,40,"人文历史"),spot(2,40,"人文历史"));c=List.of(choice(1,true,1,3,0,0),choice(2,true,1,3,0,0));check(hasCode(ConstraintPlanner.solve(config(new int[]{60,60},c,"balanced"),s),"FIXED_DAY_OVERFLOW"),"pinned day overflow");
  s=List.of(spot(1,50,"人文历史"),spot(2,50,"人文历史"),spot(3,50,"人文历史"));c=List.of(choice(1,true,0,3,0,0),choice(2,true,0,3,0,0),choice(3,true,0,3,0,0));check(hasCode(ConstraintPlanner.solve(config(new int[]{60,60},c,"balanced"),s),"REQUIRED_TOTAL_OVERFLOW"),"total mandatory overflow");
  s=List.of(spot(1,40,"人文历史"),spot(2,40,"人文历史"),spot(3,40,"人文历史"));check(hasCode(ConstraintPlanner.solve(config(new int[]{60,60},c,"balanced"),s),"REQUIRED_CANNOT_PACK"),"indivisible packing conflict proven");
  check(hasCode(ConstraintPlanner.solve(config(new int[]{30},List.of(choice(1,false,0,1,0,0)),"balanced"),List.of(spot(1,31,"人文历史"))),"NO_VISIT_FITS"),"no empty saveable proposal");
  var timingConfig=config(new int[]{60},List.of(choice(1,true,0,1,10,5),choice(2,true,0,1,10,5)),"balanced");var timingSpots=List.of(spot(1,15,"人文历史"),spot(2,15,"人文历史"));var timing=ConstraintPlanner.solve(timingConfig,timingSpots);var timingPlan=list(timing.get("plans")).get(0);var timingItems=list(timingPlan.get("items"));check(timingItems.get(0).get("arrival").equals("08:10")&&timingItems.get(1).get("departure").equals("08:55")&&list(timingPlan.get("days")).get(0).get("finish_time").equals("09:00"),"first/last travel and rest are timed");valid(timing,timingConfig,timingSpots);
  var optionalConfig=config(new int[]{60},List.of(choice(1,false,0,1,0,0),choice(2,false,0,5,0,0)),"interest");var optional=ConstraintPlanner.solve(optionalConfig,List.of(spot(1,60,"人文历史"),spot(2,60,"街巷生活")));check(((Number)list(list(optional.get("plans")).get(0).get("items")).get(0).get("spot_id")).longValue()==2,"user priority participates in soft score");
  var fixedConfig=config(new int[]{60,60},List.of(choice(1,false,2,1,0,0)),"balanced");var fixed=ConstraintPlanner.solve(fixedConfig,List.of(spot(1,30,"人文历史")));check(list(list(fixed.get("plans")).get(0).get("items")).stream().anyMatch(x->n(x.get("day_number"))==2),"fixed day automatically required");
  for(int test=0;test<240;test++){
   int dayCount=1+random.nextInt(4),count=1+random.nextInt(8);int[]capacities=new int[dayCount];for(int d=0;d<dayCount;d++)capacities[d]=30+15*random.nextInt(15);
   List<Map<String,Object>>rs=new ArrayList<>(),rc=new ArrayList<>(),mandatory=new ArrayList<>();Map<Long,Integer>costs=new HashMap<>();
   for(long id=1;id<=count;id++){int visit=15+15*random.nextInt(8),travel=random.nextInt(4)*5,rest=random.nextInt(3)*5,fixedDay=random.nextInt(4)==0?1+random.nextInt(dayCount):0;boolean required=random.nextBoolean();var ch=choice(id,required,fixedDay,1+random.nextInt(5),travel,rest);rs.add(spot(id,visit,id%2==0?"人文历史":"山水风景"));rc.add(ch);costs.put(id,visit+travel+rest);if(required||fixedDay>0)mandatory.add(ch);}
   var cfg=config(capacities,rc,new String[]{"balanced","interest","compact"}[test%3]);var result=ConstraintPlanner.solve(cfg,rs);boolean feasible=brute(0,mandatory,costs,capacities.clone());if(mandatory.isEmpty())feasible=rc.stream().anyMatch(ch->Arrays.stream(capacities).anyMatch(cap->costs.get(((Number)ch.get("spot_id")).longValue())<=cap));
   check(feasible==result.get("status").equals("feasible"),"brute mandatory feasibility case "+test+" "+result.get("status"));if(feasible)valid(result,cfg,rs);
   check(result.equals(ConstraintPlanner.solve(cfg,rs)),"deterministic repeated input "+test);
  }
  var hardSpots=new ArrayList<Map<String,Object>>();var hardChoices=new ArrayList<Map<String,Object>>();int[]hardCosts={47,49,51,53,55,57,59,61,63,65,67,69,71,73,47,49,71,73};for(int i=0;i<hardCosts.length;i++){hardSpots.add(spot(i+1,hardCosts[i],"人文历史"));hardChoices.add(choice(i+1,true,0,1,0,0));}
  var limited=ConstraintPlanner.solve(config(new int[]{180,180,180,180,180,180},hardChoices,"balanced"),hardSpots);check(limited.get("status").equals("search_limited")&&hasCode(limited,"SEARCH_LIMIT"),"node bound is distinct from proven conflict");check(list(limited.get("plans")).isEmpty()&&n(map(limited.get("search")).get("hard_nodes"))==50000,"node limit respected and no partial result saveable");
  var manySpots=new ArrayList<Map<String,Object>>();var manyChoices=new ArrayList<Map<String,Object>>();for(long i=1;i<=18;i++){manySpots.add(spot(i,60+(int)(i%3)*15,"人文历史"));manyChoices.add(choice(i,false,0,1+(int)(i%5),15,10));}
  long begin=System.nanoTime();var large=ConstraintPlanner.solve(config(new int[]{600,600,600,600,600,600,600},manyChoices,"balanced"),manySpots);long ms=(System.nanoTime()-begin)/1_000_000;check(large.get("status").equals("feasible")&&ms<5000,"bounded 18 candidate workload "+ms+"ms");check(Boolean.TRUE.equals(map(large.get("search")).get("limited")),"beam pruning is declared");valid(large,config(new int[]{600,600,600,600,600,600,600},manyChoices,"balanced"),manySpots);
  System.out.println("PASS "+checks+" algorithm assertions; 240 exhaustive feasibility comparisons; large workload "+ms+"ms");
 }
}
