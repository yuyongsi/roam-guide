package cn.edu.travel;

import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
    final Database db;
    final Auth auth;
    final LoginThrottle throttle;
    public ApiController(Database db,Auth auth,LoginThrottle throttle){this.db=db;this.auth=auth;this.throttle=throttle;}
    static String text(Map<String,Object> body,String key,int min,int max) {
        String value=Objects.toString(body.get(key),"").trim();
        if(value.length()<min || value.length()>max)throw new ApiError(400,keyName(key)+"请填写 "+min+"～"+max+" 个字符");
        return value;
    }
    static String keyName(String key) {
        return switch(key){case "username"->"用户名";case "body"->"评论";case "title"->"标题";case "intro"->"简介";default->"内容";};
    }
    static String raw(Map<String,Object> body,String key){return Objects.toString(body.get(key),"");}
    static long number(Object value) {
        try{return Long.parseLong(String.valueOf(value));}catch(Exception e){throw new ApiError(400,"请选择有效的内容");}
    }
    static String publicLookup(String name){return "SELECT a.id FROM "+name+" a JOIN cities c ON c.id=a.city_id WHERE a.id=? AND a.published=1 AND c.published=1";}
    static String table(String type) {
        return switch(type){case "spot"->"spots";case "guide"->"guides";default->throw new ApiError(400,"内容类型不正确");};
    }
    Map<String,Object> session(HttpServletRequest req) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("user",auth.publicUser(auth.current(req))); out.put("csrf",auth.csrf(req));return out;
    }
    @GetMapping("/session") Map<String,Object> current(HttpServletRequest req){return session(req);}
    @PostMapping("/auth/register") Map<String,Object> register(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        String name=text(body,"username",3,24),password=raw(body,"password");
        if(!name.matches("[\\p{L}\\p{N}_]+"))throw new ApiError(400,"用户名只支持文字、数字和下划线");
        Passwords.validate(password);
        if(db.count("SELECT count(*) FROM users WHERE username=?",name)>0)throw new ApiError(409,"这个用户名已被使用，请换一个");
        long id=db.insert("INSERT INTO users(username,password_hash) VALUES(?,?)",name,Passwords.hash(password));
        auth.signIn(req,db.one("SELECT * FROM users WHERE id=?",id));return session(req);
    }
    @PostMapping("/auth/login") Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        String name=text(body,"username",1,24),password=raw(body,"password");
        if(password.length()>128)throw new ApiError(400,"密码长度不正确");
        String source=req.getRemoteAddr();
        synchronized(throttle){
            throttle.check(source,name);
            var rows=db.list("SELECT * FROM users WHERE username=?",name);
            if(rows.isEmpty() || !Passwords.matches(password,String.valueOf(rows.get(0).get("password_hash")))){
                throttle.failed(source,name);
                throw new ApiError(401,"用户名或密码不正确");
            }
            throttle.success(source,name);
            if(!"ACTIVE".equals(rows.get(0).get("status")))throw new ApiError(403,"账号已被封禁，请联系管理员");
            auth.signIn(req,rows.get(0));return session(req);
        }
    }
    @PostMapping("/auth/logout") Map<String,Object> logout(HttpServletRequest req) {
        var session=req.getSession(false);if(session!=null)session.invalidate();return session(req);
    }
    @PostMapping("/user/password") Map<String,Object> password(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        var user=auth.require(req);
        if(!Passwords.matches(raw(body,"old_password"),user.get("password_hash").toString()))throw new ApiError(400,"原密码不正确");
        String next=raw(body,"new_password");Passwords.validate(next);
        if(next.equals(raw(body,"old_password")))throw new ApiError(400,"新密码需要与原密码不同");
        db.update("UPDATE users SET password_hash=?,auth_version=auth_version+1,must_change_password=0 WHERE id=?",Passwords.hash(next),user.get("id"));
        auth.signIn(req,db.one("SELECT * FROM users WHERE id=?",user.get("id")));return session(req);
    }
    @GetMapping("/cities") Object cities() {
        return db.list("SELECT c.*,(SELECT count(*) FROM spots s WHERE s.city_id=c.id AND s.published=1 AND c.published=1) AS spot_count,(SELECT count(*) FROM guides g WHERE g.city_id=c.id AND g.published=1 AND c.published=1) AS guide_count FROM cities c WHERE c.published=1 ORDER BY sort_order,id");
    }
    @GetMapping("/spots/{id}") Object spot(@PathVariable long id) {
        return db.transaction(tx->{
            var item=db.one("SELECT s.*,c.name AS city_name,c.slug AS city_slug,c.province FROM spots s JOIN cities c ON c.id=s.city_id WHERE s.id=? AND s.published=1 AND c.published=1",id);
            SpotMaps.decorate(item);
            item.put("gallery",db.decode(item.get("gallery")));
            item.put("guides",db.list("SELECT g.*,c.name AS city_name FROM guides g JOIN guide_spots gs ON gs.guide_id=g.id JOIN cities c ON c.id=g.city_id WHERE gs.spot_id=? AND g.published=1 AND c.published=1 ORDER BY g.id",id));
            item.put("publication",PublicationMetadata.forPublic(db,"spot",id,item));
            return item;
        });
    }
    @GetMapping("/guides/{id}") Object guide(@PathVariable long id) {
        return db.transaction(tx->{
            var item=db.one("SELECT g.*,c.name AS city_name,c.slug AS city_slug,c.province FROM guides g JOIN cities c ON c.id=g.city_id WHERE g.id=? AND g.published=1 AND c.published=1",id);
            item.put("sections",db.decode(item.get("sections")));
            item.put("spots",db.list("SELECT s.*,c.name AS city_name FROM spots s JOIN guide_spots gs ON gs.spot_id=s.id JOIN cities c ON c.id=s.city_id WHERE gs.guide_id=? AND s.published=1 AND c.published=1 ORDER BY gs.sort_order",id));
            item.put("publication",PublicationMetadata.forPublic(db,"guide",id,item));
            return item;
        });
    }
    @GetMapping("/guides/{id}/comments") Object comments(@PathVariable long id) {
        db.one(publicLookup("guides"),id);
        return db.list("SELECT m.id,m.guide_id,m.user_id,m.body,m.created_at,m.updated_at,u.username FROM comments m JOIN users u ON u.id=m.user_id WHERE m.guide_id=? ORDER BY m.created_at DESC,m.id DESC",id);
    }
    @PostMapping("/guides/{id}/comments") Object comment(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        var user=auth.require(req);db.one(publicLookup("guides"),id);
        String content=text(body,"body",1,1000);
        long key=db.insert("INSERT INTO comments(guide_id,user_id,body) VALUES(?,?,?)",id,user.get("id"),content);
        return Map.of("id",key,"message","评论已发表");
    }
    @DeleteMapping("/comments/{id}") Object deleteComment(@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);
        var comment=db.one("SELECT user_id FROM comments WHERE id=?",id);
        if(!"ADMIN".equals(user.get("role")) && number(user.get("id"))!=number(comment.get("user_id")))throw new ApiError(403,"只能删除自己的评论");
        db.update("DELETE FROM comments WHERE id=?",id);return Map.of("message","评论已删除");
    }
    @GetMapping("/me/comments") Object myComments(HttpServletRequest req) {
        var user=auth.require(req);
        return db.list("SELECT m.*,g.title AS guide_title,g.image,g.published,c.name AS city_name FROM comments m JOIN guides g ON g.id=m.guide_id JOIN cities c ON c.id=g.city_id WHERE m.user_id=? ORDER BY m.created_at DESC,m.id DESC",user.get("id"));
    }
    @GetMapping("/me/favorites") Object favorites(HttpServletRequest req) {
        var user=auth.require(req);
        return db.list("SELECT 'spot' AS type,s.id,s.title,s.intro,s.image,s.published,c.name AS city_name,f.created_at AS created_at FROM spot_favorites f JOIN spots s ON s.id=f.spot_id JOIN cities c ON c.id=s.city_id WHERE f.user_id=? UNION ALL SELECT 'guide' AS type,g.id,g.title,g.intro,g.image,g.published,c.name AS city_name,f.created_at AS created_at FROM guide_favorites f JOIN guides g ON g.id=f.guide_id JOIN cities c ON c.id=g.city_id WHERE f.user_id=? ORDER BY created_at DESC",user.get("id"),user.get("id"));
    }
    @PutMapping("/favorites/{type}/{id}") Object addFavorite(@PathVariable String type,@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);String name=table(type);db.one(publicLookup(name),id);
        db.update("INSERT OR IGNORE INTO "+type+"_favorites(user_id,"+type+"_id) VALUES(?,?)",user.get("id"),id);
        return Map.of("message","已加入收藏");
    }
    @DeleteMapping("/favorites/{type}/{id}") Object removeFavorite(@PathVariable String type,@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);table(type);
        db.update("DELETE FROM "+type+"_favorites WHERE user_id=? AND "+type+"_id=?",user.get("id"),id);
        return Map.of("message","已取消收藏");
    }
    @GetMapping("/admin/{entity}") Object adminList(@PathVariable String entity,HttpServletRequest req) {
        auth.admin(req);
        return switch(entity) {
            case "cities" -> db.list("SELECT * FROM cities ORDER BY sort_order,id");
            case "users" -> db.list("SELECT id,username,role,status,must_change_password,created_at FROM users ORDER BY id DESC");
            case "comments" -> db.list("SELECT m.*,u.username,g.title AS guide_title FROM comments m JOIN users u ON u.id=m.user_id JOIN guides g ON g.id=m.guide_id ORDER BY m.created_at DESC,m.id DESC");
            case "spots" -> {
                var rows=db.list("SELECT s.*,c.name AS city_name FROM spots s JOIN cities c ON c.id=s.city_id ORDER BY s.id DESC");
                for(var row:rows)row.put("gallery",db.decode(row.get("gallery")));
                yield rows;
            }
            case "guides" -> {
                var rows=db.list("SELECT g.*,c.name AS city_name FROM guides g JOIN cities c ON c.id=g.city_id ORDER BY g.id DESC");
                for(var g:rows) {
                    g.put("sections",db.decode(g.get("sections")));
                    g.put("spot_ids",db.list("SELECT spot_id FROM guide_spots WHERE guide_id=? ORDER BY sort_order",g.get("id")).stream().map(v->v.get("spot_id")).toList());
                }
                yield rows;
            }
            default -> throw new ApiError(404,"没有这个管理页面");
        };
    }
    @PostMapping("/admin/users/{id}/status") Object userStatus(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        auth.admin(req);var target=db.one("SELECT * FROM users WHERE id=?",id);
        if("ADMIN".equals(target.get("role")))throw new ApiError(400,"管理员账号不能通过这里封禁");
        String status=raw(body,"status");
        if(!Set.of("ACTIVE","BANNED").contains(status))throw new ApiError(400,"账号状态不正确");
        db.update("UPDATE users SET status=?,auth_version=auth_version+1 WHERE id=?",status,id);
        return Map.of("message",status.equals("BANNED")?"账号已封禁，历史评论保留":"账号已解封");
    }
    @PostMapping("/admin/users/{id}/reset-password") Object reset(@PathVariable long id,HttpServletRequest req) {
        auth.admin(req);var target=db.one("SELECT role FROM users WHERE id=?",id);
        if("ADMIN".equals(target.get("role")))throw new ApiError(400,"请通过个人中心修改管理员密码");
        String password="Trip-"+Passwords.token().substring(0,12);
        db.update("UPDATE users SET password_hash=?,must_change_password=1,auth_version=auth_version+1 WHERE id=?",Passwords.hash(password),id);
        return Map.of("temporary_password",password,"message","密码已重置，用户下次登录需要修改");
    }
    @PostMapping("/admin/{type}") Object create(@PathVariable String type,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        auth.admin(req);return save(type,0,body);
    }
    @PutMapping("/admin/{type}/{id}") Object edit(@PathVariable String type,@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        auth.admin(req);return save(type,id,body);
    }
    @PutMapping("/admin/{type}/{id}/publication") Object publication(@PathVariable String type,@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        auth.admin(req);String table=contentTable(type);long revision=positiveRevision(body);
        if(!(body.get("published") instanceof Boolean published))throw new ApiError(400,"请选择有效的发布状态");
        return db.transaction(status->{
            int changed=db.update("UPDATE "+table+" SET published=?,revision=revision+1 WHERE id=? AND revision=?",published?1:0,id,revision);
            if(changed==0){db.one("SELECT id FROM "+table+" WHERE id=?",id);throw new ApiError(409,"内容已在另一个窗口更新，请刷新后再操作");}
            return Map.of("message",published?"内容已发布":"内容已下架");
        });
    }
    private static String contentTable(String type){return switch(type){case "spots"->"spots";case "guides"->"guides";default->throw new ApiError(404,"内容类型不正确");};}
    static long positiveRevision(Map<String,Object> body){long revision=number(body.get("revision"));if(revision<1)throw new ApiError(400,"请刷新内容后再操作");return revision;}
    private Object save(String type,long id,Map<String,Object> body) {
        String tableName=contentTable(type);long revision=id>0?positiveRevision(body):0;
        if(id>0)db.one("SELECT id FROM "+tableName+" WHERE id=?",id);
        long city=number(body.get("city_id"));db.one("SELECT id FROM cities WHERE id=?",city);
        String title=text(body,"title",1,100),intro=text(body,"intro",1,500),image=text(body,"image",1,300);
        if(!image.matches("^/(assets|uploads)/[A-Za-z0-9_.-]+$"))throw new ApiError(400,"请上传图片或选择已有图片");
        String source=raw(body,"source_url").trim();
        if(source.length()>1000 || (!source.isEmpty() && !source.startsWith("https://")))throw new ApiError(400,"资料链接请使用 HTTPS 地址");
        int published=Boolean.TRUE.equals(body.get("published")) || "1".equals(String.valueOf(body.get("published")))?1:0;
        long result=db.transaction(status -> {
            // 先取得写锁并比较版本；后续验证失败时，版本与全部关联修改一起回滚。
            if(id>0&&db.update("UPDATE "+tableName+" SET revision=revision+1 WHERE id=? AND revision=?",id,revision)==0)
                throw new ApiError(409,"内容已在另一个窗口更新，请刷新后再编辑");
            long key=id;
            if(type.equals("spots")) {
                String description=text(body,"description",1,6000),highlights=text(body,"highlights",0,3000),
                    transportation=text(body,"transportation",0,3000),tips=text(body,"tips",0,3000);
                String mapQuery=body.containsKey("map_query")?text(body,"map_query",0,200):(id>0?String.valueOf(db.one("SELECT map_query FROM spots WHERE id=?",id).get("map_query")):"");
                if(id>0){var old=db.one("SELECT title,city_id,map_query FROM spots WHERE id=?",id);if(mapQuery.equals(old.get("map_query"))&&(!title.equals(old.get("title"))||city!=number(old.get("city_id"))))mapQuery="";}
                if(id==0)key=db.insert("INSERT INTO spots(city_id,title,intro,description,highlights,transportation,tips,image,source_url,published) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    city,title,intro,description,highlights,transportation,tips,image,source,published);
                else db.update("UPDATE spots SET city_id=?,title=?,intro=?,description=?,highlights=?,transportation=?,tips=?,image=?,source_url=?,published=? WHERE id=?",
                    city,title,intro,description,highlights,transportation,tips,image,source,published,id);
                db.update("UPDATE spots SET map_query=? WHERE id=?",mapQuery,key);
            } else {
                Object sections=body.get("sections");
                if(!(sections instanceof java.util.List<?> values) || values.isEmpty() || values.size()>15)throw new ApiError(400,"请至少填写一段攻略正文，最多 15 段");
                for(Object v:(java.util.List<?>)sections) {
                    if(!(v instanceof Map<?,?> section) || Objects.toString(section.get("title"),"").isBlank() || Objects.toString(section.get("body"),"").isBlank())throw new ApiError(400,"每段攻略都需要小节标题和正文");
                    if(Objects.toString(section.get("title"),"").length()>100 || Objects.toString(section.get("body"),"").length()>10000)throw new ApiError(400,"攻略段落过长，请分段填写");
                }
                String subtitle=text(body,"subtitle",0,150),encoded=db.encode(sections);
                if(id==0)key=db.insert("INSERT INTO guides(city_id,title,subtitle,intro,image,sections,source_url,published) VALUES(?,?,?,?,?,?,?,?)",city,title,subtitle,intro,image,encoded,source,published);
                else db.update("UPDATE guides SET city_id=?,title=?,subtitle=?,intro=?,image=?,sections=?,source_url=?,published=? WHERE id=?",city,title,subtitle,intro,image,encoded,source,published,id);
                db.update("DELETE FROM guide_spots WHERE guide_id=?",key);
                Object ids=body.getOrDefault("spot_ids",java.util.List.of());
                if(!(ids instanceof java.util.List<?>))throw new ApiError(400,"关联景点格式不正确");
                Set<Long> distinct=new LinkedHashSet<>();
                for(Object value:(java.util.List<?>)ids)distinct.add(number(value));
                int order=0;
                for(long spot:distinct) {
                    if(db.count("SELECT count(*) FROM spots WHERE id=? AND city_id=?",spot,city)==0)
                        throw new ApiError(400,"关联景点必须属于攻略所选的地区");
                    db.update("INSERT INTO guide_spots(guide_id,spot_id,sort_order) VALUES(?,?,?)",key,spot,order++);
                }
            }
            String theme=body.containsKey("theme")?text(body,"theme",1,20):(id>0?db.one("SELECT theme FROM "+tableName+" WHERE id=?",id).get("theme").toString():"人文历史");
            if(!Set.of("山水风景","街巷生活","人文历史").contains(theme))throw new ApiError(400,"请选择有效的旅行主题");
            long duration=body.containsKey("duration_minutes")?number(body.get("duration_minutes")):(id>0?number(db.one("SELECT duration_minutes FROM "+tableName+" WHERE id=?",id).get("duration_minutes")):120);
            if(duration<15 || duration>20160)throw new ApiError(400,"建议时长请填写 15～20160 分钟");
            db.update("UPDATE "+tableName+" SET theme=?,duration_minutes=? WHERE id=?",theme,duration,key);
            if(type.equals("spots") && body.containsKey("gallery")) {
                Object gallery=body.get("gallery");
                if(!(gallery instanceof java.util.List<?> photos) || photos.size()>8)throw new ApiError(400,"相册最多添加 8 张图片");
                for(Object path:(java.util.List<?>)gallery)if(!(path instanceof String p) || !p.matches("^/(assets|uploads)/[A-Za-z0-9_.-]+$"))throw new ApiError(400,"相册请使用已上传或预置的图片");
                db.update("UPDATE spots SET gallery=? WHERE id=?",db.encode(gallery),key);
            }
            return key;
        });
        return Map.of("id",result,"message","内容已保存");
    }
    @PostMapping("/admin/uploads") Object upload(@RequestParam("file") MultipartFile file,HttpServletRequest req) throws Exception {
        auth.admin(req);
        if(file.isEmpty() || file.getSize()>5*1024*1024)throw new ApiError(400,"请选择 5 MB 以内的 JPG 或 PNG 图片");
        BufferedImage original=ImageIO.read(file.getInputStream());
        if(original==null || (long)original.getWidth()*original.getHeight()>30_000_000)throw new ApiError(400,"图片无法读取，请换一张 JPG 或 PNG 图片");
        double scale=Math.min(1,1600.0/original.getWidth());
        int width=Math.max(1,(int)(original.getWidth()*scale)),height=Math.max(1,(int)(original.getHeight()*scale));
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();graphics.setColor(Color.WHITE);graphics.fillRect(0,0,width,height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(original,0,0,width,height,null);graphics.dispose();
        String filename=UUID.randomUUID()+".jpg";
        ImageIO.write(image,"jpg",TravelApplication.DATA.resolve("uploads").resolve(filename).toFile());
        return Map.of("url","/uploads/"+filename);
    }
}
