package cn.edu.travel;

import java.util.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static cn.edu.travel.ApiController.*;

/** 旅行社区：正文为纯文本；公开互动与个人编辑分别检查状态和归属。 */
@RestController
@RequestMapping("/api")
public class SocialController {
    final Database db; final Auth auth;
    public SocialController(Database db,Auth auth){this.db=db;this.auth=auth;}
    static final Set<String> CATEGORIES=Set.of("游记","提问","经验");
    final String select="SELECT p.*,u.username,c.name AS city_name,(SELECT count(*) FROM social_likes l WHERE l.post_id=p.id) AS like_count,(SELECT count(*) FROM social_comments m WHERE m.post_id=p.id) AS comment_count FROM social_posts p JOIN users u ON u.id=p.user_id LEFT JOIN cities c ON c.id=p.city_id";
    private Map<String,Object> visible(long id,HttpServletRequest req) {
        var p=db.one(select+" WHERE p.id=? AND p.status<>'DELETED'",id);var user=auth.current(req);
        boolean owner=user!=null&&number(user.get("id"))==number(p.get("user_id"));
        boolean admin=user!=null&&"ADMIN".equals(user.get("role"));
        if(!"PUBLISHED".equals(p.get("status"))&&!owner&&!admin)throw new ApiError(404,"这篇帖子暂时无法查看");
        p.put("is_owner",owner);p.put("liked",user!=null&&db.count("SELECT count(*) FROM social_likes WHERE post_id=? AND user_id=?",id,user.get("id"))>0);
        return p;
    }
    private void published(long id){db.one("SELECT id FROM social_posts WHERE id=? AND status='PUBLISHED'",id);}
    @GetMapping("/social/posts") Object list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") long city,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="1") int page) {
        if(!category.isEmpty()&&!CATEGORIES.contains(category))throw new ApiError(400,"请选择有效的帖子分类");
        q=q.trim();if(q.length()>100)throw new ApiError(400,"搜索词最多 100 字");page=Math.max(1,Math.min(100000,page));
        String like="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String where=" WHERE p.status='PUBLISHED' AND (?=0 OR p.city_id=?) AND (?='' OR p.category=?) AND (p.title LIKE ? ESCAPE '\\' OR p.body LIKE ? ESCAPE '\\' OR u.username LIKE ? ESCAPE '\\')";
        Object[] args={city,city,category,category,like,like,like};
        long total=db.count("SELECT count(*) FROM social_posts p JOIN users u ON u.id=p.user_id"+where,args);
        List<Object> params=new ArrayList<>(Arrays.asList(args));params.add(6);params.add((page-1)*6);
        var items=db.list(select+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",params.toArray());
        for(var p:items){String content=Objects.toString(p.remove("body"),"");p.put("excerpt",content.substring(0,Math.min(160,content.length())));}
        return Map.of("items",items,"total",total,"page",page,"size",6);
    }
    @GetMapping("/social/posts/{id}") Object detail(@PathVariable long id,HttpServletRequest req){return visible(id,req);}
    @PostMapping("/social/posts") Object create(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        var u=auth.require(req);long id=db.transaction(status->{
            if(db.count("SELECT count(*) FROM social_posts WHERE user_id=? AND status<>'DELETED'",u.get("id"))>=100)throw new ApiError(400,"最多保留 100 篇帖子，请先整理已有内容");
            var v=values(body);return db.insert("INSERT INTO social_posts(user_id,city_id,category,title,body,image) VALUES(?,?,?,?,?,?)",u.get("id"),v.get("city_id"),v.get("category"),v.get("title"),v.get("body"),v.get("image"));
        });return Map.of("id",id,"message","帖子已发布");
    }
    private Map<String,Object> values(Map<String,Object> body) {
        String category=text(body,"category",1,20),title=text(body,"title",3,80),content=text(body,"body",10,12000),image=text(body,"image",0,300);
        if(!CATEGORIES.contains(category))throw new ApiError(400,"请选择游记、提问或经验");
        if(!image.isEmpty()&&!image.matches("^/(assets|uploads)/[A-Za-z0-9_.-]+$"))throw new ApiError(400,"封面请使用已上传的图片");
        long city=number(body.getOrDefault("city_id",0));if(city<0)throw new ApiError(400,"请选择有效地区");
        if(city>0)db.one("SELECT id FROM cities WHERE id=? AND published=1",city);
        Map<String,Object> out=new LinkedHashMap<>();out.put("city_id",city==0?null:city);out.put("category",category);out.put("title",title);out.put("body",content);out.put("image",image);return out;
    }
    @PutMapping("/social/posts/{id}") Object update(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        var user=auth.require(req);
        db.transaction(status->{
            var p=db.one("SELECT user_id FROM social_posts WHERE id=? AND status<>'DELETED'",id);
            if(number(p.get("user_id"))!=number(user.get("id")))throw new ApiError(403,"只能编辑自己的帖子");
            var v=values(body);int n=db.update("UPDATE social_posts SET city_id=?,category=?,title=?,body=?,image=?,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND revision=?",v.get("city_id"),v.get("category"),v.get("title"),v.get("body"),v.get("image"),id,number(body.get("revision")));
            if(n==0)throw new ApiError(409,"帖子已更新，请刷新后再编辑");return null;
        });return Map.of("message","帖子已保存","id",id);
    }
    @DeleteMapping("/social/posts/{id}") Object delete(@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);var p=db.one("SELECT user_id FROM social_posts WHERE id=? AND status<>'DELETED'",id);
        if(number(p.get("user_id"))!=number(user.get("id")))throw new ApiError(403,"只能删除自己的帖子");
        db.update("UPDATE social_posts SET status='DELETED',revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",id);return Map.of("message","帖子已删除");
    }
    @GetMapping("/social/posts/{id}/comments") Object comments(@PathVariable long id,HttpServletRequest req){visible(id,req);return db.list("SELECT m.*,u.username FROM social_comments m JOIN users u ON u.id=m.user_id WHERE post_id=? ORDER BY m.created_at DESC,m.id DESC",id);}
    @PostMapping("/social/posts/{id}/comments") Object comment(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        var user=auth.require(req);long key=db.transaction(status->{published(id);return db.insert("INSERT INTO social_comments(post_id,user_id,body) VALUES(?,?,?)",id,user.get("id"),text(body,"body",1,1000));});return Map.of("id",key,"message","评论已发表");
    }
    @DeleteMapping("/social/comments/{id}") Object deleteComment(@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);var m=db.one("SELECT user_id FROM social_comments WHERE id=?",id);
        if(!"ADMIN".equals(user.get("role"))&&number(m.get("user_id"))!=number(user.get("id")))throw new ApiError(403,"只能删除自己的评论");
        db.update("DELETE FROM social_comments WHERE id=?",id);return Map.of("message","评论已删除");
    }
    @PutMapping("/social/posts/{id}/like") Object like(@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);return db.transaction(status->{published(id);db.update("INSERT OR IGNORE INTO social_likes(post_id,user_id) VALUES(?,?)",id,user.get("id"));return Map.of("liked",true,"like_count",db.count("SELECT count(*) FROM social_likes WHERE post_id=?",id));});
    }
    @DeleteMapping("/social/posts/{id}/like") Object unlike(@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);visible(id,req);db.update("DELETE FROM social_likes WHERE post_id=? AND user_id=?",id,user.get("id"));return Map.of("liked",false,"like_count",db.count("SELECT count(*) FROM social_likes WHERE post_id=?",id));
    }
    @GetMapping("/me/posts") Object mine(HttpServletRequest req){var user=auth.require(req);return db.list(select+" WHERE p.user_id=? AND p.status<>'DELETED' ORDER BY p.id DESC",user.get("id"));}
    @GetMapping("/admin/posts") Object admin(@RequestParam(defaultValue="") String q,HttpServletRequest req) {
        auth.admin(req);q=q.trim();if(q.length()>100)throw new ApiError(400,"搜索词最多 100 字");
        String like="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        return db.list(select+" WHERE p.status<>'DELETED' AND (p.title LIKE ? ESCAPE '\\' OR u.username LIKE ? ESCAPE '\\') ORDER BY p.id DESC",like,like);
    }
    @PutMapping("/admin/posts/{id}/status") Object moderate(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        auth.admin(req);db.one("SELECT id FROM social_posts WHERE id=? AND status<>'DELETED'",id);String state=raw(body,"status");
        if(!Set.of("PUBLISHED","HIDDEN").contains(state))throw new ApiError(400,"请选择有效状态");
        db.update("UPDATE social_posts SET status=?,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",state,id);
        return Map.of("message",state.equals("PUBLISHED")?"帖子已恢复公开":"帖子已下架，作者仍可查看自己的内容");
    }
    @PostMapping("/social/uploads") Object upload(@RequestParam("file") MultipartFile file,HttpServletRequest req) throws Exception {
        auth.require(req);if(file.isEmpty()||file.getSize()>5*1024*1024)throw new ApiError(400,"请选择 5 MB 以内的 JPG 或 PNG 图片");
        BufferedImage original;
        try(ImageInputStream stream=ImageIO.createImageInputStream(file.getInputStream())) {
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new ApiError(400,"图片无法读取，请使用 JPG 或 PNG");
            ImageReader reader=readers.next();try{
                if(!Set.of("JPEG","PNG").contains(reader.getFormatName().toUpperCase(Locale.ROOT)))throw new ApiError(400,"只支持 JPG 或 PNG 图片");
                reader.setInput(stream);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1||h<1||(long)w*h>30_000_000L||w>15000||h>15000)throw new ApiError(400,"图片尺寸过大，请缩小后上传");original=reader.read(0);
            }finally{reader.dispose();}
        }catch(java.io.IOException e){throw new ApiError(400,"图片文件不完整或无法读取，请换一张 JPG 或 PNG 图片");}
        double scale=Math.min(1,1600.0/Math.max(original.getWidth(),original.getHeight()));int w=Math.max(1,(int)(original.getWidth()*scale)),h=Math.max(1,(int)(original.getHeight()*scale));
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,w,h);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(original,0,0,w,h,null);g.dispose();
        String name="community-"+UUID.randomUUID()+".jpg";ImageIO.write(image,"jpg",TravelApplication.DATA.resolve("uploads").resolve(name).toFile());return Map.of("url","/uploads/"+name);
    }
}
