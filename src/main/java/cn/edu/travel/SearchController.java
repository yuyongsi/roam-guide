package cn.edu.travel;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SearchController {
    private final Database db;
    public SearchController(Database db){this.db=db;}
    @GetMapping("/search") Object search(@RequestParam(defaultValue="spot") String type,@RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="0") long city,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="6") int size,
        @RequestParam(defaultValue="") String theme,@RequestParam(defaultValue="") String duration,@RequestParam(defaultValue="relevance") String sort){
        String table=ApiController.table(type);q=q.trim();
        if(q.length()>100)throw new ApiError(400,"搜索词请控制在 100 个字符以内");
        if(!Set.of("","山水风景","街巷生活","人文历史").contains(theme))throw new ApiError(400,"请选择有效的旅行主题");
        if(!Set.of("","half","day","multi").contains(duration))throw new ApiError(400,"请选择有效的游玩时长");
        if(!Set.of("relevance","editor").contains(sort))throw new ApiError(400,"请选择有效的排序方式");
        final String query=q;final int current=Math.max(1,Math.min(100000,page)),limit=Math.max(1,Math.min(24,size));
        List<String> terms=SearchText.terms(q);
        if(!q.isEmpty()&&terms.isEmpty())return result(List.of(),0,current,limit,type,sort,terms);
        return db.transaction(status->{
            boolean searching=!query.isEmpty();
            // 首条语句先获取写锁，防止 SQLite 从读事务升级写事务的竞争失败。
            if(searching){db.update("UPDATE search_dirty SET item_id=item_id WHERE item_id=-1");SearchIndex.flush(db);}
            String where=" FROM "+table+" a JOIN cities c ON c.id=a.city_id";
            var params=new ArrayList<Object>();
            if(searching){where+=" JOIN search_fts ON search_fts.item_id=a.id AND search_fts.type=?";params.add(type);}
            where+=" WHERE a.published=1 AND c.published=1 AND (?=0 OR a.city_id=?)";params.add(city);params.add(city);
            if(searching){where+=" AND search_fts MATCH ?";params.add(SearchText.match(terms));}
            where+=" AND (?='' OR a.theme=?) AND (?='' OR (?='half' AND a.duration_minutes<=240) OR (?='day' AND a.duration_minutes>240 AND a.duration_minutes<=600) OR (?='multi' AND a.duration_minutes>600))";
            Collections.addAll(params,theme,theme,duration,duration,duration,duration);
            long total=db.count("SELECT count(*)"+where,params.toArray());
            params.add(limit);params.add((current-1)*limit);
            String order=searching&&sort.equals("relevance")?"bm25(search_fts,8.0,3.0,1.0,5.0,0.0,0.0),a.id":"a.id";
            var items=db.list("SELECT a.*,c.name AS city_name,c.slug AS city_slug,c.province"+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",params.toArray());
            var publicItems=new ArrayList<Map<String,Object>>();
            for(var row:items){
                var item=new LinkedHashMap<String,Object>();for(String key:List.of("id","title","intro","image","city_id","published","theme","duration_minutes","city_name","city_slug","province"))item.put(key,row.get(key));
                if(searching){
                    String body=SearchIndex.body(db,type,row),location=row.get("city_name")+" · "+row.get("province");
                    var fields=new ArrayList<String>();for(var f:List.of(Map.entry("title",row.get("title").toString()),Map.entry("intro",row.get("intro").toString()),Map.entry("body",body),Map.entry("location",location)))if(SearchText.hits(f.getValue(),terms))fields.add(f.getKey());
                    String snippet=fields.contains("body")?body:row.get("intro").toString();
                    item.put("search",Map.of("title",SearchText.segments(row.get("title").toString(),terms,0),"location",SearchText.segments(location,terms,0),"snippet",SearchText.segments(snippet,terms,120),"fields",fields));
                }
                publicItems.add(item);
            }
            return result(publicItems,total,current,limit,type,searching?sort:"editor",terms);
        });
    }
    private static Map<String,Object> result(Object items,long total,int page,int size,String type,String sort,List<String> terms){
        return Map.of("items",items,"total",total,"page",page,"size",size,"type",type,"sort",sort,"terms",terms,"engine","fts5-character-phrase");
    }
}
