package cn.edu.travel;

import java.util.*;
import java.net.URI;
import java.nio.file.Files;
import org.springframework.core.io.ClassPathResource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static cn.edu.travel.ApiController.*;

@RestController
@RequestMapping("/api/admin")
public class CityController {
    private final Database db;private final Auth auth;private final CityCatalog catalog;
    public CityController(Database db,Auth auth,CityCatalog catalog){this.db=db;this.auth=auth;this.catalog=catalog;}
    @GetMapping("/city-catalog") Object options(HttpServletRequest req){auth.admin(req);return catalog.options(db);}
    @PostMapping("/cities") Object create(@RequestBody Map<String,Object> body,HttpServletRequest req){
        auth.admin(req);String province=text(body,"province_code",6,6),code=text(body,"region_code",6,6);
        var chosen=catalog.region(province,code);String official=chosen.get("name").toString();
        String name=official.endsWith("市")?official.substring(0,official.length()-1):official;
        final String displayName="special".equals(chosen.get("kind"))?official.replace("特别行政区",""):name;
        return db.transaction(status->{
            int created=db.update("INSERT INTO cities(region_code,province_code,slug,name,province,tagline,description,image,sort_order,published) VALUES(?,?,?,?,?,'','','',(SELECT coalesce(max(sort_order),0)+1 FROM cities),0) ON CONFLICT(region_code) DO NOTHING",
                code,province,"city-"+code,displayName,chosen.get("province_name"));
            var row=db.one("SELECT id,name FROM cities WHERE region_code=?",code);
            return Map.of("id",row.get("id"),"created",created==1,"message",created==1?"城市已加入，请完善资料后展示":"该城市已收录，已定位到现有资料");
        });
    }
    @PutMapping("/cities/{id}") Object edit(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req){
        auth.admin(req);long revision=positiveRevision(body);
        return db.transaction(status->{
            if(db.update("UPDATE cities SET revision=revision+1 WHERE id=? AND revision=?",id,revision)==0){db.one("SELECT id FROM cities WHERE id=?",id);throw new ApiError(409,"目的地已在另一个窗口更新，请重新载入后再编辑");}
            var old=db.one("SELECT * FROM cities WHERE id=?",id);
            String tagline=text(body,"tagline",0,80),description=text(body,"description",0,1000),image=body.containsKey("image")?text(body,"image",0,300):old.get("image").toString();
            String source=text(body,"food_source",0,1000);
            validateImage(image);
            if(!source.isEmpty())try{URI uri=URI.create(source);if(!"https".equals(uri.getScheme())||uri.getHost()==null)throw new IllegalArgumentException();}catch(Exception e){throw new ApiError(400,"资料链接请使用有效的 HTTPS 地址");}
            if(number(old.get("published"))==1)requireBasics(tagline,description,image);
            db.update("UPDATE cities SET tagline=?,description=?,image=?,food=?,travel_advice=?,visit_advice=?,food_source=? WHERE id=?",tagline,description,image,text(body,"food",0,1500),text(body,"travel_advice",0,1500),text(body,"visit_advice",0,1500),source,id);
            return Map.of("message","目的地资料已保存");
        });
    }
    static void requireBasics(String tagline,String description,String image){
        if(tagline.isBlank()||description.isBlank()||image.isBlank())throw new ApiError(400,"请先补齐城市封面、目的地短句和城市介绍");
    }
    static void validateImage(String image){
        if(image.isEmpty())return;
        if(!image.matches("^/(assets|uploads)/[A-Za-z0-9_.-]+$"))throw new ApiError(400,"请使用上传的城市封面或已有图片");
        boolean exists=image.startsWith("/assets/")?new ClassPathResource("static"+image).exists():Files.isRegularFile(TravelApplication.DATA.resolve("uploads").resolve(image.substring("/uploads/".length())));
        if(!exists)throw new ApiError(400,"城市封面文件已不存在，请重新上传");
    }
    @PutMapping("/cities/{id}/publication") Object publish(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req){
        auth.admin(req);long revision=positiveRevision(body);
        if(!Boolean.TRUE.equals(body.get("published")))throw new ApiError(400,"这里用于展示已完善的目的地，不支持删除或整体隐藏已有城市");
        return db.transaction(status->{
            if(db.update("UPDATE cities SET revision=revision+1 WHERE id=? AND revision=?",id,revision)==0){db.one("SELECT id FROM cities WHERE id=?",id);throw new ApiError(409,"目的地资料已更新，请重新载入后再展示");}
            var c=db.one("SELECT * FROM cities WHERE id=?",id);requireBasics(c.get("tagline").toString(),c.get("description").toString(),c.get("image").toString());validateImage(c.get("image").toString());
            if(db.count("SELECT count(*) FROM spots WHERE city_id=? AND published=1",id)==0)throw new ApiError(400,"请先为这座城市添加并发布至少一个景点，再展示到前台");
            db.update("UPDATE cities SET published=1 WHERE id=?",id);return Map.of("message","目的地已展示，相关筛选和内容同步更新");
        });
    }
}
