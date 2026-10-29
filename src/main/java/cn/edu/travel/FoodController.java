package cn.edu.travel;

import java.util.*;
import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static cn.edu.travel.ApiController.*;

/** 地方美食是编辑维护的资料；收藏和显示数量均来自当前账号的真实记录。 */
@RestController
@RequestMapping("/api")
public class FoodController {
    final Database db; final Auth auth;
    static final Set<String> CATEGORIES=Set.of("小吃","主食","菜肴","甜点");
    public FoodController(Database db,Auth auth){this.db=db;this.auth=auth;}
    private Object uid(HttpServletRequest req){var user=auth.current(req);return user==null?0:user.get("id");}
    private String columns(){return "f.*,c.name AS city_name,c.slug AS city_slug,c.province,(SELECT count(*) FROM food_favorites z WHERE z.food_id=f.id) AS favorite_count,EXISTS(SELECT 1 FROM food_favorites z WHERE z.food_id=f.id AND z.user_id=?) AS favorited";}
    @GetMapping("/foods") Object foods(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") long city,
        @RequestParam(defaultValue="") String category,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="6") int size,HttpServletRequest req) {
        q=q.trim();if(q.length()>100)throw new ApiError(400,"搜索词请控制在 100 个字符以内");
        if(!category.isEmpty()&&!CATEGORIES.contains(category))throw new ApiError(400,"请选择有效的美食类型");
        page=Math.max(1,Math.min(100000,page));size=Math.max(1,Math.min(24,size));
        String like="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String where=" FROM foods f JOIN cities c ON c.id=f.city_id WHERE f.published=1 AND c.published=1 AND (?=0 OR f.city_id=?) AND (?='' OR f.category=?) AND (f.title LIKE ? ESCAPE '\\' OR f.intro LIKE ? ESCAPE '\\' OR c.name LIKE ? ESCAPE '\\')";
        Object[] args={city,city,category,category,like,like,like};long total=db.count("SELECT count(*)"+where,args);
        List<Object> values=new ArrayList<>();values.add(uid(req));values.addAll(Arrays.asList(args));values.add(size);values.add((page-1)*size);
        return Map.of("items",db.list("SELECT "+columns()+where+" ORDER BY f.id LIMIT ? OFFSET ?",values.toArray()),"total",total,"page",page,"size",size);
    }
    @GetMapping("/foods/{id}") Object food(@PathVariable long id,HttpServletRequest req) {
        return db.transaction(tx->{
            var item=db.one("SELECT "+columns()+" FROM foods f JOIN cities c ON c.id=f.city_id WHERE f.id=? AND f.published=1 AND c.published=1",uid(req),id);
            item.put("publication",PublicationMetadata.forPublic(db,"food",id,item));
            return item;
        });
    }
    @GetMapping("/me/food-favorites") Object favorites(HttpServletRequest req) {
        var user=auth.require(req);
        return db.list("SELECT "+columns()+",z.created_at AS saved_at FROM food_favorites z JOIN foods f ON f.id=z.food_id JOIN cities c ON c.id=f.city_id WHERE z.user_id=? ORDER BY z.created_at DESC,f.id DESC",user.get("id"),user.get("id"));
    }
    @PutMapping("/food-favorites/{id}") Object favorite(@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);return db.transaction(status->{
            db.one(publicLookup("foods"),id);
            db.update("INSERT OR IGNORE INTO food_favorites(user_id,food_id) VALUES(?,?)",user.get("id"),id);return favoriteResult(id,true);
        });
    }
    @DeleteMapping("/food-favorites/{id}") Object unfavorite(@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);db.update("DELETE FROM food_favorites WHERE user_id=? AND food_id=?",user.get("id"),id);return favoriteResult(id,false);
    }
    private Object favoriteResult(long id,boolean value){return Map.of("favorited",value,"favorite_count",db.count("SELECT count(*) FROM food_favorites WHERE food_id=?",id),"message",value?"美食已加入收藏":"已取消美食收藏");}
    @GetMapping("/admin/foods") Object admin(HttpServletRequest req){auth.admin(req);return db.list("SELECT f.*,c.name AS city_name FROM foods f JOIN cities c ON c.id=f.city_id ORDER BY f.id DESC");}
    @PostMapping("/admin/foods") Object create(@RequestBody Map<String,Object> body,HttpServletRequest req){auth.admin(req);return save(0,body);}
    @PutMapping("/admin/foods/{id}") Object edit(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req){auth.admin(req);db.one("SELECT id FROM foods WHERE id=?",id);return save(id,body);}
    private Object save(long id,Map<String,Object> body) {
        return db.transaction(status->{
        long city=number(body.get("city_id"));db.one("SELECT id FROM cities WHERE id=?",city);
        String title=text(body,"title",1,80),category=text(body,"category",1,10),intro=text(body,"intro",1,500),description=text(body,"description",1,6000),
            taste=text(body,"taste",0,2000),tips=text(body,"tips",0,3000),image=text(body,"image",0,300),source=text(body,"source_url",0,1000);
        if(!CATEGORIES.contains(category))throw new ApiError(400,"请选择有效的美食类型");
        if(!image.isEmpty()&&!image.matches("^/(assets|uploads)/[A-Za-z0-9_.-]+$"))throw new ApiError(400,"请使用上传图片或预置照片");
        if(!source.isEmpty())try{URI uri=URI.create(source);if(!"https".equals(uri.getScheme())||uri.getHost()==null)throw new IllegalArgumentException();}catch(Exception e){throw new ApiError(400,"资料链接请使用有效的 HTTPS 地址");}
        int published=Boolean.TRUE.equals(body.get("published"))||"1".equals(String.valueOf(body.get("published")))?1:0;
        long key=id;
        if(id==0)key=db.insert("INSERT INTO foods(city_id,title,category,intro,description,taste,tips,image,source_url,published) VALUES(?,?,?,?,?,?,?,?,?,?)",city,title,category,intro,description,taste,tips,image,source,published);
        else {
            int changed=db.update("UPDATE foods SET city_id=?,title=?,category=?,intro=?,description=?,taste=?,tips=?,image=?,source_url=?,published=?,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND revision=?",city,title,category,intro,description,taste,tips,image,source,published,id,number(body.get("revision")));
            if(changed==0)throw new ApiError(409,"这道美食已在另一个窗口更新，请刷新后再编辑");
        }
        return Map.of("id",key,"message","美食内容已保存");
        });
    }
}
