package cn.edu.travel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import jakarta.servlet.http.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static cn.edu.travel.ApiController.*;
/** Owner-controlled draft and review workflow for public travel content. */
@RestController
@RequestMapping("/api")
class ContributionsController {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ContributionsController.class);
    private final Database db;
    private final Auth auth;
    private static final Set<String> KINDS=Set.of("spot","guide","food");
    private static final Map<String,Set<String>> FIELDS=Map.of(
    "spot",Set.of("city_id","title","intro","description","highlights","transportation","tips","image","source_url","theme","duration_minutes","map_query","gallery"),
    "guide",Set.of("city_id","title","subtitle","intro","image","source_url","theme","duration_minutes","sections","spot_ids"),
    "food",Set.of("city_id","title","category","intro","description","taste","tips","image","source_url"));
    ContributionsController(Database db,Auth auth){
        this.db=db;
        this.auth=auth;
    }
    private static long n(Object x){
        try{
            return new java.math.BigDecimal(String.valueOf(x)).longValueExact();
        }
        catch(Exception e){
            throw new ApiError(400,"请选择有效的整数");
        }
    }
    private static String kind(String k){
        if(!KINDS.contains(k))throw new ApiError(400,"内容类型不正确");
        return k;
    }
    private static String table(String k){
        return switch(kind(k)){
            case "spot"->"spots";
            case "guide"->"guides";
            default->"foods";
        };
    }
    private Map<String,Object> clean(Map<String,Object> in,String k,long ownerId,String operation,Map<String,Object> baseSnapshot){
        if(in==null)throw new ApiError(400,"投稿内容格式不正确");
        for(String key:in.keySet())if(!FIELDS.get(kind(k)).contains(key))throw new ApiError(400,"投稿包含不支持的字段："+key);
        Map<String,Integer> limits=Map.ofEntries(Map.entry("title",100),Map.entry("intro",500),Map.entry("description",6000),Map.entry("highlights",3000),Map.entry("transportation",3000),Map.entry("tips",3000),Map.entry("taste",2000),Map.entry("subtitle",150),Map.entry("category",10),Map.entry("image",300),Map.entry("source_url",1000),Map.entry("theme",20),Map.entry("map_query",200));
        for(var e:limits.entrySet())if(in.containsKey(e.getKey())){
            Object v=in.get(e.getKey());
            if(!(v instanceof String s)||s.length()>e.getValue())throw new ApiError(400,"请检查“"+e.getKey()+"”字段格式或长度");
        }
        for(String key:List.of("city_id","duration_minutes"))if(in.containsKey(key)){
            Object v=in.get(key);
            if(!(v instanceof Number)||n(v)<(key.equals("city_id")?1:0)||(key.equals("duration_minutes")&&n(v)>20160))throw new ApiError(400,"请检查“"+key+"”字段");
        }
        if(in.containsKey("gallery")){
            Object v=in.get("gallery");
            if(!(v instanceof List<?> l)||l.size()>8)throw new ApiError(400,"相册最多添加 8 张图片");
            for(Object x:l){
                if(!(x instanceof String s)||s.length()>300)throw new ApiError(400,"相册图片地址格式不正确");
                validateDraftImage(ownerId,operation,baseSnapshot,s);
            }
        }
        if(in.containsKey("image"))validateDraftImage(ownerId,operation,baseSnapshot,Objects.toString(in.get("image"),""));
        if(in.containsKey("spot_ids")){
            Object v=in.get("spot_ids");
            if(!(v instanceof List<?> l)||l.size()>50)throw new ApiError(400,"关联景点格式不正确");
            Set<Long> seen=new HashSet<>();
            for(Object x:l){
                if(!(x instanceof Number)||n(x)<1)throw new ApiError(400,"关联景点格式不正确");
                if(!seen.add(n(x)))throw new ApiError(400,"关联景点不能重复，请移除重复景点后再保存");
            }
        }
        if(in.containsKey("sections")){
            Object v=in.get("sections");
            if(!(v instanceof List<?> l)||l.size()>15)throw new ApiError(400,"攻略最多 15 个小节");
            for(Object x:l){
                if(!(x instanceof Map<?,?> m))throw new ApiError(400,"攻略小节格式不正确");
                for(String f:List.of("title","body")){
                    Object value=m.get(f);
                    if(value!=null&&(!(value instanceof String s)||s.length()>(f.equals("title")?100:10000)))throw new ApiError(400,"攻略小节格式或长度不正确");
                }
                for(Object key:m.keySet())if(!Set.of("title","body").contains(key))throw new ApiError(400,"攻略小节包含不支持的字段");
            }
        }
        return new LinkedHashMap<>(in);
    }
    private Map<String,Object> canonical(String k,long id){
        Map<String,Object> m=db.one("SELECT * FROM "+table(k)+" WHERE id=?",id);
        Map<String,Object> p=new LinkedHashMap<>();
        for(String key:FIELDS.get(k))if(m.containsKey(key))p.put(key,m.get(key));
        if(k.equals("spot"))p.put("gallery",db.decode(m.get("gallery")));
        if(k.equals("guide")){
            p.put("sections",db.decode(m.get("sections")));
            p.put("spot_ids",db.list("SELECT spot_id FROM guide_spots WHERE guide_id=? ORDER BY sort_order",id).stream().map(x->x.get("spot_id")).toList());
        }
        return p;
    }
    private Map<String,Object> currentTarget(String k,long id){
        String t=table(k);
        return db.one("SELECT x.*,c.name AS city_name,c.published AS city_published FROM "+t+" x JOIN cities c ON c.id=x.city_id WHERE x.id=?",id);
    }
    private Map<String,Object> targetForEdit(String k,long id){
        var row=db.one("SELECT x.*,c.name AS city_name FROM "+table(k)+" x JOIN cities c ON c.id=x.city_id WHERE x.id=? AND x.published=1 AND c.published=1",id);
        return row;
    }
    private Object details(long id,boolean admin){
        var c=db.one("SELECT c.*,u.username FROM contributions c JOIN users u ON u.id=c.owner_id WHERE c.id=?",id);
        if(admin&&c.get("deleted_at")!=null)throw new ApiError(404,"没有找到这条投稿");
        c.put("base_snapshot",db.decode(c.get("base_snapshot")));
        c.put("payload",db.decode(c.get("payload")));
        c.put("reviews",db.list("SELECT r.decision,r.note,u.username AS reviewer_name,r.created_at FROM contribution_reviews r JOIN users u ON u.id=r.reviewer_id WHERE r.contribution_id=? ORDER BY r.id",id));
        if(admin && "UPDATE".equals(c.get("operation")) && c.get("target_id")!=null){
            try{
                var t=currentTarget(String.valueOf(c.get("kind")),n(c.get("target_id")));
                t.put("payload",canonical(String.valueOf(c.get("kind")),n(c.get("target_id"))));
                c.put("current_target",t);
            }
            catch(ApiError e){
                if(e.status!=404)throw e;
                c.put("current_target",null);
            }
        }
        return c;
    }
    @GetMapping("/me/contributions") Object mine(@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String kind,
    @RequestParam(defaultValue="false") boolean trash,
    @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,HttpServletRequest req){
        var u=auth.require(req);
        if(!status.isEmpty()&&!Set.of("DRAFT","PENDING","RETURNED","APPROVED").contains(status))throw new ApiError(400,"投稿状态不正确");
        if(!kind.isEmpty())kind(kind);
        page=Math.max(1,Math.min(100000,page));
        size=Math.max(1,Math.min(50,size));
        String where=" FROM contributions x LEFT JOIN cities c ON c.id=coalesce(json_extract(x.payload,'$.city_id'),json_extract(x.base_snapshot,'$.city_id')) WHERE x.owner_id=? AND "+(trash?"x.deleted_at IS NOT NULL":"x.deleted_at IS NULL")+" AND (?='' OR x.status=?) AND (?='' OR x.kind=?)";
        Object[] args={
            u.get("id"),status,status,kind,kind};
        long total=db.count("SELECT count(*)"+where,args);
        var items=db.list("SELECT x.id,x.kind,x.operation,x.status,x.revision,x.review_note,x.published_id,x.target_id,x.updated_at,x.deleted_at,c.name AS city_name,coalesce(json_extract(x.payload,'$.title'),json_extract(x.base_snapshot,'$.title'),'未命名草稿') AS title"+where+" ORDER BY x.updated_at DESC,x.id DESC LIMIT ? OFFSET ?",u.get("id"),status,status,kind,kind,size,(page-1)*size);
        long trashCount=db.count("SELECT count(*) FROM contributions WHERE owner_id=? AND deleted_at IS NOT NULL",u.get("id"));
        return Map.of("items",items,"total",total,"page",page,"size",size,"trash_count",trashCount);
    }
    /** Authenticated users create their own drafts; UPDATE baselines are captured under the write lock. */
    @PostMapping("/me/contributions") Object create(@RequestBody Map<String,Object> b,HttpServletRequest req){
        var u=auth.require(req);
        String k=kind(Objects.toString(b.get("kind"),"")),op=Objects.toString(b.get("operation"),"");
        if(!Set.of("CREATE","UPDATE").contains(op))throw new ApiError(400,"投稿操作不正确");
        return db.transaction(tx->{
            db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
            if(db.count("SELECT count(*) FROM contributions WHERE owner_id=? AND deleted_at IS NULL AND status IN ('DRAFT','RETURNED')",u.get("id"))>=100)throw new ApiError(400,"每个账号最多保留 100 个未完成草稿");
            long target=0,revision=0;
            Map<String,Object> base=new LinkedHashMap<>();
            if(op.equals("UPDATE")){
                target=n(b.get("target_id"));
                var row=targetForEdit(k,target);
                revision=n(row.get("revision"));
                base=canonical(k,target);
            }
            Map<String,Object> payload;
            if(b.get("payload") instanceof Map<?,?> m)payload=clean(cast(m),k,n(u.get("id")),op,base);
            else if(b.containsKey("payload"))throw new ApiError(400,"投稿内容格式不正确");
            else payload=op.equals("UPDATE")?new LinkedHashMap<>(base):Map.of();
            long id=db.insert("INSERT INTO contributions(owner_id,kind,operation,target_id,target_revision,base_snapshot,payload) VALUES(?,?,?,?,?,?,?)",u.get("id"),k,op,op.equals("UPDATE")?target:null,op.equals("UPDATE")?revision:null,db.encode(base),db.encode(payload));
            return details(id,false);
        });
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Map<?,?> m){
        return (Map<String,Object>)m;
    }
    @GetMapping("/me/contributions/{id}") Object detail(@PathVariable long id,HttpServletRequest req){
        owner(id,req);
        return details(id,false);
    }
    /** Conceal another user's submission as not found, including for detail and mutations. */
    private Map<String,Object> owner(long id,HttpServletRequest req){
        var u=auth.require(req);
        var row=db.one("SELECT * FROM contributions WHERE id=?",id);
        if(n(row.get("owner_id"))!=n(u.get("id")))throw new ApiError(404,"没有找到这条投稿");
        return row;
    }
    private Map<String,Object> activeOwner(long id,HttpServletRequest req){
        var row=owner(id,req);
        if(row.get("deleted_at")!=null)throw new ApiError(409,"投稿已在回收站，请先恢复后再操作");
        return row;
    }
    @PutMapping("/me/contributions/{id}") Object edit(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){
        var u=auth.require(req);
        return db.transaction(tx->{
            db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
            var c=activeOwner(id,req);
            if(!Set.of("DRAFT","RETURNED").contains(c.get("status")))throw new ApiError(409,"当前投稿状态不能编辑");
            Map<String,Object> p=clean(b.get("payload") instanceof Map<?,?> m?cast(m):null,String.valueOf(c.get("kind")),n(u.get("id")),String.valueOf(c.get("operation")),(Map<String,Object>)db.decode(c.get("base_snapshot")));
            if(db.update("UPDATE contributions SET payload=?,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND owner_id=? AND revision=? AND status IN ('DRAFT','RETURNED')",db.encode(p),id,u.get("id"),positiveRevision(b))==0)throw new ApiError(409,"投稿已更新，请刷新后再编辑");
            return details(id,false);
        });
    }
    @PostMapping("/me/contributions/{id}/submit") Object submit(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){
        return db.transaction(tx->{
            db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
            var c=activeOwner(id,req);
            if(!Set.of("DRAFT","RETURNED").contains(c.get("status")))throw new ApiError(409,"当前投稿状态不能提交");
            if(n(c.get("revision"))!=positiveRevision(b))throw new ApiError(409,"投稿已更新，请刷新后再提交");
            validateSubmission(c);
            if(db.update("UPDATE contributions SET status='PENDING',revision=revision+1,submitted_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),review_note='' WHERE id=? AND revision=? AND status IN ('DRAFT','RETURNED')",id,b.get("revision"))==0)throw new ApiError(409,"投稿已更新，请刷新后再提交");
            return details(id,false);
        });
    }
    @PostMapping("/me/contributions/{id}/withdraw") Object withdraw(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){
        return db.transaction(tx->{
            db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
            var c=activeOwner(id,req);
            if(!"PENDING".equals(c.get("status")))throw new ApiError(409,"只有待审核投稿可以撤回");
            if(db.update("UPDATE contributions SET status='DRAFT',revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND owner_id=? AND revision=? AND status='PENDING'",id,c.get("owner_id"),positiveRevision(b))==0)throw new ApiError(409,"投稿已被审核或更新，请刷新后查看");
            return details(id,false);
        });
    }
    @PostMapping("/me/contributions/{id}/rebase") Object rebase(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){
        return db.transaction(tx->{
            db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
            var c=activeOwner(id,req);
            if(!"UPDATE".equals(c.get("operation"))||!Set.of("DRAFT","RETURNED").contains(c.get("status")))throw new ApiError(409,"当前投稿不能更新对照版本");
            if(n(c.get("revision"))!=positiveRevision(b))throw new ApiError(409,"投稿已更新，请刷新后再操作");
            var t=targetForEdit(String.valueOf(c.get("kind")),n(c.get("target_id")));
            var base=canonical(String.valueOf(c.get("kind")),n(c.get("target_id")));
            if(db.update("UPDATE contributions SET target_revision=?,base_snapshot=?,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND revision=? AND status IN ('DRAFT','RETURNED')",t.get("revision"),db.encode(base),id,b.get("revision"))==0)throw new ApiError(409,"投稿已更新，请刷新后操作");
            return details(id,false);
        });
    }
    @PostMapping("/me/contributions/{id}/trash") Object trash(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){
        return db.transaction(tx->{
            db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
            var c=activeOwner(id,req);
            if(!Set.of("DRAFT","RETURNED").contains(c.get("status")))throw new ApiError(409,"只有草稿或需修改的投稿可以回收");
            long revision=positiveRevision(b);
            if(db.update("UPDATE contributions SET deleted_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND owner_id=? AND revision=? AND deleted_at IS NULL AND status IN ('DRAFT','RETURNED')",id,c.get("owner_id"),revision)==0)throw new ApiError(409,"投稿已更新，请刷新后再回收");
            return details(id,false);
        });
    }
    @PostMapping("/me/contributions/{id}/restore") Object restore(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){
        return db.transaction(tx->{
            db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
            var c=owner(id,req);
            if(c.get("deleted_at")==null||!Set.of("DRAFT","RETURNED").contains(c.get("status")))throw new ApiError(409,"这条投稿不能从回收站恢复");
            long revision=positiveRevision(b);
            if(n(c.get("revision"))!=revision)throw new ApiError(409,"投稿已更新，请刷新后恢复");
            if(db.count("SELECT count(*) FROM contributions WHERE owner_id=? AND deleted_at IS NULL AND status IN ('DRAFT','RETURNED')",c.get("owner_id"))>=100)throw new ApiError(400,"活跃草稿已达到 100 条，请先回收其他草稿");
            if(db.update("UPDATE contributions SET deleted_at=NULL,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND owner_id=? AND revision=? AND deleted_at IS NOT NULL AND status IN ('DRAFT','RETURNED')",id,c.get("owner_id"),revision)==0)throw new ApiError(409,"投稿已更新，请刷新后恢复");
            return details(id,false);
        });
    }
    @DeleteMapping("/me/contributions/{id}") Object purge(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){
        return db.transaction(tx->{
            db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
            var c=owner(id,req);
            if(c.get("deleted_at")==null||!Set.of("DRAFT","RETURNED").contains(c.get("status")))throw new ApiError(409,"只有回收站中的草稿可以永久删除");
            if(db.update("DELETE FROM contributions WHERE id=? AND owner_id=? AND revision=? AND deleted_at IS NOT NULL AND status IN ('DRAFT','RETURNED')",id,c.get("owner_id"),positiveRevision(b))==0)throw new ApiError(409,"投稿已更新，请刷新后删除");
            return Map.of("message","投稿已永久删除");
        });
    }
    @GetMapping("/contributions/source/{kind}/{targetId}") Object source(@PathVariable String kind,@PathVariable long targetId,HttpServletRequest req){
        auth.require(req);
        var t=targetForEdit(kind,targetId);
        return Map.of("kind",kind,"target_id",targetId,"target_revision",t.get("revision"),"payload",canonical(kind,targetId));
    }
    @GetMapping("/contributions/duplicates") Object duplicates(@RequestParam String kind,@RequestParam(name="city_id") String cityValue,
        @RequestParam(defaultValue="") String title,@RequestParam(name="exclude_id",defaultValue="0") String excludeValue,HttpServletRequest req){
        auth.require(req);
        String k=kind(kind);
        long cityId=n(cityValue),excludeId=n(excludeValue);
        if(cityId<1||excludeId<0)throw new ApiError(400,"请选择有效的地区或排除项");
        if(title.length()>100)throw new ApiError(400,"标题请控制在 100 个字符以内");
        var city=db.list("SELECT name FROM cities WHERE id=? AND published=1",cityId);
        if(city.isEmpty())throw new ApiError(400,"请选择有效的公开地区");
        String normalized=normalizeTitle(title);
        if(normalized.isEmpty())return Map.of("items",List.of(),"exact",false);
        String table=table(k);
        var rows=db.list("SELECT x.id,x.title,c.name AS city_name FROM "+table+" x JOIN cities c ON c.id=x.city_id WHERE x.city_id=? AND x.id<>? AND x.published=1 AND c.published=1 ORDER BY x.id LIMIT 500",cityId,excludeId);
        String cityName=String.valueOf(city.get(0).get("name"));
        String queryCore=withoutCityPrefix(normalized,normalizeTitle(cityName));
        List<Map<String,Object>> matches=new ArrayList<>();
        boolean exact=false;
        for(var row:rows){
            String candidate=normalizeTitle(String.valueOf(row.get("title")));
            String candidateCore=withoutCityPrefix(candidate,normalizeTitle(cityName));
            String type=null;
            if(candidate.equals(normalized)){type="exact";exact=true;}
            else if(Math.min(candidateCore.length(),queryCore.length())>=2&&(candidateCore.contains(queryCore)||queryCore.contains(candidateCore)))type="similar";
            if(type!=null){
                Map<String,Object> item=new LinkedHashMap<>();
                item.put("id",row.get("id"));item.put("title",row.get("title"));item.put("city_name",row.get("city_name"));
                item.put("url",switch(k){case "spot"->"/spot/";case "guide"->"/guide/";default->"/food/";}+row.get("id"));
                item.put("match_type",type);matches.add(item);
            }
        }
        matches.sort(Comparator.comparingInt((Map<String,Object> x)->"exact".equals(x.get("match_type"))?0:1).thenComparingLong(x->n(x.get("id"))));
        if(matches.size()>5)matches=new ArrayList<>(matches.subList(0,5));
        return Map.of("items",matches,"exact",exact);
    }
    private static String normalizeTitle(String value){
        String folded=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out=new StringBuilder();
        folded.codePoints().filter(Character::isLetterOrDigit).forEach(out::appendCodePoint);
        return out.toString();
    }
    private static String withoutCityPrefix(String title,String city){
        if(!city.isEmpty()&&title.startsWith(city))return title.substring(city.length());
        return title;
    }
    private void validateSubmission(Map<String,Object> c){
        Map<String,Object> p=merged(c);
        String k=String.valueOf(c.get("kind"));
        long city=n(p.get("city_id"));
        db.one("SELECT id FROM cities WHERE id=? AND published=1",city);
        required(p,"title",1,100);
        required(p,"intro",1,500);
        String image=Objects.toString(p.get("image"),"");
        if(!k.equals("food")&&image.isBlank())throw new ApiError(400,"请为景点或攻略添加封面图片");
        if(k.equals("spot")){
            required(p,"description",1,6000);
            required(p,"highlights",0,3000);
            required(p,"transportation",0,3000);
            required(p,"tips",0,3000);
            validateImageRefs(c,p,List.of("image","gallery"));
        }
        if(k.equals("guide")){
            required(p,"subtitle",0,150);
            validateImageRefs(c,p,List.of("image"));
            Object sections=p.get("sections");
            if(!(sections instanceof List<?> s)||s.isEmpty()||s.size()>15)throw new ApiError(400,"攻略至少需要一段正文，最多 15 段");
            for(Object x:s){
                if(!(x instanceof Map<?,?> m))throw new ApiError(400,"攻略段落格式不正确");
                required(cast(m),"title",1,100);
                required(cast(m),"body",1,10000);
            }
            Object ids=p.get("spot_ids");
            if(!(ids instanceof List<?> list)||list.isEmpty())throw new ApiError(400,"攻略至少需要关联一个同城公开景点");
            Set<Long> seen=new HashSet<>();
            for(Object x:list){
                long spotId=n(x);
                if(!seen.add(spotId))throw new ApiError(400,"关联景点不能重复，请移除重复景点后再提交");
                if(db.count("SELECT count(*) FROM spots WHERE id=? AND city_id=? AND published=1",spotId,city)==0)throw new ApiError(400,"关联景点必须是同城公开景点");
            }
        }
        if(k.equals("food")){
            required(p,"category",1,10);
            if(!FoodController.CATEGORIES.contains(p.get("category")))throw new ApiError(400,"请选择有效的美食类型");
            required(p,"description",1,6000);
            required(p,"taste",0,2000);
            required(p,"tips",0,3000);
            validateImageRefs(c,p,List.of("image"));
        }
        required(p,"source_url",0,1000);
        String url=Objects.toString(p.get("source_url"),"");
        if(!url.isEmpty())try{
            var uri=java.net.URI.create(url);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null)throw new IllegalArgumentException();
        }
        catch(Exception e){
            throw new ApiError(400,"资料链接请使用有效的 HTTPS 地址");
        }
        if(!k.equals("food")){
            String theme=Objects.toString(p.get("theme"),"");
            if(!Set.of("山水风景","街巷生活","人文历史").contains(theme))throw new ApiError(400,"请选择有效的旅行主题");
            long duration=n(p.get("duration_minutes"));
            if(duration<15||duration>20160)throw new ApiError(400,"建议时长请填写 15～20160 分钟");
        }
        if(k.equals("spot")){
            required(p,"map_query",0,200);
            Object gallery=p.getOrDefault("gallery",List.of());
            if(!(gallery instanceof List<?> photos)||photos.size()>8)throw new ApiError(400,"相册最多添加 8 张图片");
        }
        for(String f:List.of("image"))if(Objects.toString(p.get(f),"").length()>300)throw new ApiError(400,"图片地址过长");
    }
    private static void required(Map<String,Object> p,String key,int min,int max){
        String v=Objects.toString(p.get(key),"").trim();
        if(v.length()<min||v.length()>max)throw new ApiError(400,"请检查“"+key+"”字段长度");
    }
    private Map<String,Object> merged(Map<String,Object> c){
        Map<String,Object> m=new LinkedHashMap<>();
        String k=String.valueOf(c.get("kind"));
        if("UPDATE".equals(c.get("operation")))m.putAll((Map<String,Object>)db.decode(c.get("base_snapshot")));
        m.putAll((Map<String,Object>)db.decode(c.get("payload")));
        if(k.equals("spot")){
            m.putIfAbsent("highlights","");
            m.putIfAbsent("transportation","");
            m.putIfAbsent("tips","");
            m.putIfAbsent("source_url","");
            m.putIfAbsent("theme","人文历史");
            m.putIfAbsent("duration_minutes",120);
            m.putIfAbsent("map_query","");
            m.putIfAbsent("gallery",List.of());
        }
        else if(k.equals("guide")){
            m.putIfAbsent("subtitle","");
            m.putIfAbsent("source_url","");
            m.putIfAbsent("theme","人文历史");
            m.putIfAbsent("duration_minutes",120);
            m.putIfAbsent("sections",List.of());
            m.putIfAbsent("spot_ids",List.of());
        }
        else{
            m.putIfAbsent("description","");
            m.putIfAbsent("taste","");
            m.putIfAbsent("tips","");
            m.putIfAbsent("image","");
            m.putIfAbsent("source_url","");
        }
        return m;
    }
    private void validateImageRefs(Map<String,Object> c,Map<String,Object> p,List<String> fields){
        for(String f:fields){
            Object v=p.get(f);
            if(f.equals("gallery")&&v instanceof List<?> l){
                for(Object x:l)validateImage(c,Objects.toString(x,""));
            }
            else if(v!=null&&!Objects.toString(v,"").isBlank())validateImage(c,Objects.toString(v,""));
        }
    }
    private Map<String,Integer> privateImageReferences(long ownerId){
        Map<String,Integer> counts=new HashMap<>();
        for(var row:db.list("SELECT payload,base_snapshot FROM contributions WHERE owner_id=?",ownerId)){
            countPrivateImages(db.decode(row.get("payload")),counts);
            countPrivateImages(db.decode(row.get("base_snapshot")),counts);
        }
        return counts;
    }
    private static void countPrivateImages(Object value,Map<String,Integer> counts){
        if(!(value instanceof Map<?,?> map))return;
        countPrivateImage(map.get("image"),counts);
        Object gallery=map.get("gallery");
        if(gallery instanceof List<?> images)for(Object image:images)countPrivateImage(image,counts);
    }
    private static void countPrivateImage(Object raw,Map<String,Integer> counts){
        String path=Objects.toString(raw,"");
        String prefix="/api/contribution-images/";
        if(!path.startsWith(prefix))return;
        String key=path.substring(prefix.length());
        if(key.matches("[0-9a-fA-F-]{36}\\.jpg"))counts.merge(key,1,Integer::sum);
    }
    @GetMapping("/me/contribution-images") Object myImages(HttpServletRequest req){
        var user=auth.require(req);
        long ownerId=n(user.get("id"));
        var counts=privateImageReferences(ownerId);
        List<Map<String,Object>> items=new ArrayList<>();
        for(var row:db.list("SELECT key,created_at FROM contribution_images WHERE owner_id=? ORDER BY created_at DESC,key",ownerId)){
            String key=String.valueOf(row.get("key"));
            int references=counts.getOrDefault(key,0);
            items.add(Map.of("key",key,"url","/api/contribution-images/"+key,"created_at",row.get("created_at"),"in_use",references>0,"reference_count",references));
        }
        return Map.of("items",items,"total",db.count("SELECT count(*) FROM contribution_images WHERE owner_id=?",ownerId),"limit",100);
    }
    @DeleteMapping("/me/contribution-images/{key}") Object deleteImage(@PathVariable String key,HttpServletRequest req){
        var user=auth.require(req);
        if(!key.matches("[0-9a-fA-F-]{36}\\.jpg"))throw new ApiError(404,"没有找到这张图片");
        long ownerId=n(user.get("id"));
        Path original=TravelApplication.DATA.resolve("contribution-uploads").resolve(key);
        Path quarantine=original.resolveSibling(".delete-"+UUID.randomUUID());
        boolean[] moved={false};
        try{
            db.transaction(tx->{
                db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
                if(db.count("SELECT count(*) FROM contribution_images WHERE key=? AND owner_id=?",key,ownerId)==0)throw new ApiError(404,"没有找到这张图片");
                if(privateImageReferences(ownerId).getOrDefault(key,0)>0)throw new ApiError(409,"这张图片仍在投稿中使用，请先移除引用");
                try{
                    if(Files.exists(original)){
                        moveSameFolder(original,quarantine);
                        moved[0]=true;
                    }
                }catch(IOException e){throw new ApiError(500,"图片暂时无法删除，请稍后重试");}
                if(db.update("DELETE FROM contribution_images WHERE key=? AND owner_id=?",key,ownerId)==0)throw new ApiError(404,"没有找到这张图片");
                return null;
            });
        }catch(RuntimeException e){
            restoreQuarantine(original,quarantine,moved[0]);
            if(e instanceof ApiError api)throw api;
            throw new ApiError(500,"图片暂时无法删除，请稍后重试");
        }
        if(moved[0])try{
            Files.deleteIfExists(quarantine);
        }catch(IOException e){
            restoreQuarantine(original,quarantine,true);
            try{
                db.transaction(tx->{
                    db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
                    db.update("INSERT OR IGNORE INTO contribution_images(key,owner_id) VALUES(?,?)",key,ownerId);
                    return null;
                });
            }catch(RuntimeException ignored){LOG.warn("投稿图片删除恢复未完成；请检查私有图片登记状态。");}
            throw new ApiError(500,"图片暂时无法删除，已保留恢复信息");
        }
        return Map.of("message","图片已删除");
    }
    private static void moveSameFolder(Path from,Path to)throws IOException{
        try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException e){Files.move(from,to);}
    }
    private static void restoreQuarantine(Path original,Path quarantine,boolean moved){
        if(!moved)return;
        try{if(Files.exists(quarantine)&&!Files.exists(original))moveSameFolder(quarantine,original);}
        catch(IOException ignored){LOG.warn("投稿图片隔离文件恢复未完成；请检查私有图片存储状态。");}
    }
    private void validateImage(Map<String,Object> c,String path){
        validateDraftImage(n(c.get("owner_id")),String.valueOf(c.get("operation")),(Map<String,Object>)db.decode(c.get("base_snapshot")),path);
    }
    private void validateDraftImage(long ownerId,String operation,Map<String,Object> baseSnapshot,String path){
        if(path.isBlank())return;
        if(path.startsWith("/api/contribution-images/")){
            String key=path.substring("/api/contribution-images/".length());
            if(!key.matches("[0-9a-fA-F-]{36}\\.jpg")||db.count("SELECT count(*) FROM contribution_images WHERE key=? AND owner_id=?",key,ownerId)==0||!Files.isRegularFile(TravelApplication.DATA.resolve("contribution-uploads").resolve(key)))throw new ApiError(400,"投稿图片不可用");
            return;
        }
        if(path.matches("^/assets/[A-Za-z0-9_.-]+$")&&new org.springframework.core.io.ClassPathResource("static"+path).exists())return;
        if("UPDATE".equals(operation)&&path.matches("^/uploads/[A-Za-z0-9_.-]+$")&&isTargetImage(baseSnapshot,path)&&Files.isRegularFile(TravelApplication.DATA.resolve("uploads").resolve(path.substring("/uploads/".length()))))return;
        throw new ApiError(400,"图片只能使用预置图、原资料图片或本人上传图片");
    }
    private boolean isTargetImage(Map<String,Object>b,String path){
        if(path.equals(b.get("image")))return true;
        return b.get("gallery") instanceof List<?> l&&l.contains(path);
    }
    @GetMapping("/admin/contributions")
    Object adminList(@RequestParam(required=false) String status,
    @RequestParam(defaultValue="") String kind,@RequestParam(defaultValue="") String q,
    @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,HttpServletRequest req){
        auth.admin(req);
        if(status==null)status="PENDING";
        if(!status.isEmpty()&&!Set.of("DRAFT","PENDING","RETURNED","APPROVED").contains(status))throw new ApiError(400,"投稿状态不正确");
        if(!kind.isEmpty())kind(kind);
        q=q.trim();
        if(q.length()>100)throw new ApiError(400,"搜索词请控制在 100 个字符以内");
        page=Math.max(1,Math.min(100000,page));
        size=Math.max(1,Math.min(50,size));
        // Use a printable escape character so SQLite receives exactly one character in ESCAPE.
        String where=" FROM contributions x JOIN users u ON u.id=x.owner_id LEFT JOIN cities c ON c.id=coalesce(json_extract(x.payload,'$.city_id'),json_extract(x.base_snapshot,'$.city_id')) WHERE x.deleted_at IS NULL AND (?='' OR x.status=?) AND (?='' OR x.kind=?) AND (?='' OR u.username LIKE ? ESCAPE '!' OR coalesce(json_extract(x.payload,'$.title'),json_extract(x.base_snapshot,'$.title'),'') LIKE ? ESCAPE '!')";
        String like="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        Object[] args={
            status,status,kind,kind,q,like,like};
        long total=db.count("SELECT count(*)"+where,args);
        var items=db.list("SELECT x.id,x.kind,x.operation,x.status,x.revision,x.review_note,x.published_id,x.target_id,x.updated_at,u.username,c.name AS city_name,coalesce(json_extract(x.payload,'$.title'),json_extract(x.base_snapshot,'$.title'),'未命名草稿') AS title"+where+" ORDER BY x.updated_at DESC,x.id DESC LIMIT ? OFFSET ?",status,status,kind,kind,q,like,like,size,(page-1)*size);
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }
    @GetMapping("/admin/contributions/{id}") Object adminDetail(@PathVariable long id,HttpServletRequest req){
        auth.admin(req);
        return details(id,true);
    }
    /** The no-op migration update takes SQLite's write lock before checking PENDING and revision. */
    @PostMapping("/admin/contributions/{id}/review") Object review(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest req){
        var admin=auth.admin(req);
        String decision=Objects.toString(b.get("decision"),""),note=Objects.toString(b.get("note"),"").trim();
        if(!Set.of("APPROVED","RETURNED").contains(decision))throw new ApiError(400,"审核决定不正确");
        if((decision.equals("RETURNED")&&(note.length()<1||note.length()>1000))||(decision.equals("APPROVED")&&note.length()>1000))throw new ApiError(400,"审核意见不能超过 1000 个字符，退回时需填写修改意见");
        List<Path> copied=new ArrayList<>();
        try{
            return db.transaction(tx->{
                db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
                var c=db.one("SELECT * FROM contributions WHERE id=? AND deleted_at IS NULL",id);
                if(!"PENDING".equals(c.get("status")))throw new ApiError(409,"这条投稿已被处理");
                if(n(c.get("revision"))!=positiveRevision(b))throw new ApiError(409,"投稿已更新，请刷新后审核");
                long published=0;
                if(decision.equals("APPROVED")){
                    validateSubmission(c);
                    Map<String,Object> p=merged(c);
                    Map<String,Object> target=null;
                    if("UPDATE".equals(c.get("operation"))){
                        target=currentTarget(String.valueOf(c.get("kind")),n(c.get("target_id")));
                        if(n(target.get("published"))!=1||n(target.get("city_published"))!=1)throw new ApiError(409,"原资料或所在城市已下架，不能批准");
                        if(n(target.get("revision"))!=n(c.get("target_revision")))throw new ApiError(409,"原资料已更新，请退回作者更新对照版本后重新提交");
                        if(n(p.get("city_id"))!=n(target.get("city_id")))throw new ApiError(400,"修改建议必须保留原资料所属地区");
                    }
                    materializeImages(c,p,copied);
                    published=persist(String.valueOf(c.get("kind")),"UPDATE".equals(c.get("operation"))?n(c.get("target_id")):0,p,target);
                }
                if(db.update("UPDATE contributions SET status=?,review_note=?,published_id=?,reviewed_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),revision=revision+1 WHERE id=? AND revision=? AND status='PENDING'",decision,note,published==0?null:published,id,b.get("revision"))==0)throw new ApiError(409,"这条投稿已被处理");
                db.update("INSERT INTO contribution_reviews(contribution_id,decision,note,reviewer_id) VALUES(?,?,?,?)",id,decision,note,admin.get("id"));
                audit(admin,decision.equals("APPROVED")?"APPROVE_CONTRIBUTION":"RETURN_CONTRIBUTION",id,RequestTracking.id(req));
                String contributionTitle=Objects.toString(merged(c).get("title"),"旅行资料");
                String noticeTitle=decision.equals("APPROVED")?"你的投稿已通过审核":"你的投稿需要修改";
                String explanation=note.isEmpty()?"管理员已通过审核。":note;
                String noticeBody="《"+contributionTitle+"》"+(decision.equals("APPROVED")?"已通过审核。":"需要修改。")+"审核说明："+explanation;
                db.update("INSERT INTO notifications(user_id,kind,title,body,path) VALUES(?,'CONTRIBUTION_REVIEW',?,?,?)",c.get("owner_id"),noticeTitle,noticeBody,"/me?tab=contributions&open="+id);
                return details(id,true);
            });
        }
        catch(RuntimeException e){
            for(Path p:copied)try{
                Files.deleteIfExists(p);
            }
            catch(Exception ignored){
            }
            throw e;
        }
    }
    private void audit(Map<String,Object>a,String action,long id,String requestId){
        db.update("INSERT INTO admin_audit_logs(actor_id,actor_name,action,resource,target_id,target_label,outcome,http_status,summary,request_method,request_path,request_id) VALUES(?,?,?,?,?,'投稿', 'SUCCESS',200,?,'POST',?,?)",a.get("id"),a.get("username"),action,"contributions",id,action.equals("APPROVE_CONTRIBUTION")?"投稿已批准":"投稿已退回","/api/admin/contributions/"+id+"/review",requestId);
    }
    private void materializeImages(Map<String,Object> c,Map<String,Object> p,List<Path> copied){
        Map<String,String> made=new HashMap<>();
        for(String f:List.of("image","gallery")){
            Object v=p.get(f);
            if(f.equals("gallery")&&v instanceof List<?> l){
                List<Object> out=new ArrayList<>();
                for(Object x:l)out.add(publishImage(c,Objects.toString(x,""),copied,made));
                p.put(f,out);
            }
            else if(v!=null&&!Objects.toString(v,"").isBlank())p.put(f,publishImage(c,Objects.toString(v,""),copied,made));
        }
    }
    private String publishImage(Map<String,Object>c,String path,List<Path> copied,Map<String,String> made){
        if(!path.startsWith("/api/contribution-images/"))return path;
        String key=path.substring("/api/contribution-images/".length());
        Path src=TravelApplication.DATA.resolve("contribution-uploads").resolve(key);
        if(!Files.isRegularFile(src)||db.count("SELECT count(*) FROM contribution_images WHERE key=? AND owner_id=?",key,c.get("owner_id"))==0)throw new ApiError(400,"投稿图片不可用");
        return made.computeIfAbsent(key,k->{
            String filename=UUID.randomUUID()+".jpg";
            Path dst=TravelApplication.DATA.resolve("uploads").resolve(filename);
            try{
                Files.copy(src,dst);
                copied.add(dst);
                return "/uploads/"+filename;
            }
            catch(IOException e){
                throw new ApiError(500,"图片发布失败");
            }
        });
    }
    /** Called only inside the review transaction; writes canonical content and associations atomically. */
    private long persist(String k,long id,Map<String,Object>p,Map<String,Object>old){
        long city=n(p.get("city_id"));
        Map<String,Object> c=db.one("SELECT id FROM cities WHERE id=? AND published=1",city);
        String t=table(k);
        long key=id;
        if(k.equals("spot")){
            String[] fs={
                "title","intro","description","highlights","transportation","tips","image","source_url","theme","duration_minutes","map_query"};
            for(String f:fs)if(!p.containsKey(f))throw new ApiError(400,"投稿缺少字段："+f);
            if(id==0)key=db.insert("INSERT INTO spots(city_id,title,intro,description,highlights,transportation,tips,image,source_url,theme,duration_minutes,map_query,gallery,published) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1)",city,p.get("title"),p.get("intro"),p.get("description"),p.get("highlights"),p.get("transportation"),p.get("tips"),p.get("image"),p.get("source_url"),p.get("theme"),p.get("duration_minutes"),p.get("map_query"),db.encode(p.getOrDefault("gallery",List.of())));
            else if(db.update("UPDATE spots SET city_id=?,title=?,intro=?,description=?,highlights=?,transportation=?,tips=?,image=?,source_url=?,theme=?,duration_minutes=?,map_query=?,gallery=?,published=1,revision=revision+1 WHERE id=? AND revision=? AND published=1",city,p.get("title"),p.get("intro"),p.get("description"),p.get("highlights"),p.get("transportation"),p.get("tips"),p.get("image"),p.get("source_url"),p.get("theme"),p.get("duration_minutes"),p.get("map_query"),db.encode(p.getOrDefault("gallery",List.of())),id,old.get("revision"))==0)throw new ApiError(409,"原资料已更新或下架");
        }
        else if(k.equals("guide")){
            Object sections=p.get("sections"),ids=p.get("spot_ids");
            if(id==0)key=db.insert("INSERT INTO guides(city_id,title,subtitle,intro,image,sections,source_url,theme,duration_minutes,published) VALUES(?,?,?,?,?,?,?,?,?,1)",city,p.get("title"),p.get("subtitle"),p.get("intro"),p.get("image"),db.encode(sections),p.get("source_url"),p.get("theme"),p.get("duration_minutes"));
            else if(db.update("UPDATE guides SET city_id=?,title=?,subtitle=?,intro=?,image=?,sections=?,source_url=?,theme=?,duration_minutes=?,published=1,revision=revision+1 WHERE id=? AND revision=? AND published=1",city,p.get("title"),p.get("subtitle"),p.get("intro"),p.get("image"),db.encode(sections),p.get("source_url"),p.get("theme"),p.get("duration_minutes"),id,old.get("revision"))==0)throw new ApiError(409,"原资料已更新或下架");
            db.update("DELETE FROM guide_spots WHERE guide_id=?",key);
            int i=0;
            for(Object x:(List<?>)ids){
                if(db.count("SELECT count(*) FROM spots WHERE id=? AND city_id=? AND published=1",n(x),city)==0)throw new ApiError(409,"关联景点已下架或不属于同城");
                db.update("INSERT INTO guide_spots(guide_id,spot_id,sort_order) VALUES(?,?,?)",key,n(x),i++);
            }
        }
        else {
            if(id==0)key=db.insert("INSERT INTO foods(city_id,title,category,intro,description,taste,tips,image,source_url,published) VALUES(?,?,?,?,?,?,?,?,?,1)",city,p.get("title"),p.get("category"),p.get("intro"),p.get("description"),p.get("taste"),p.get("tips"),Objects.toString(p.get("image"),""),p.get("source_url"));
            else if(db.update("UPDATE foods SET city_id=?,title=?,category=?,intro=?,description=?,taste=?,tips=?,image=?,source_url=?,published=1,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND revision=? AND published=1",city,p.get("title"),p.get("category"),p.get("intro"),p.get("description"),p.get("taste"),p.get("tips"),Objects.toString(p.get("image"),""),p.get("source_url"),id,old.get("revision"))==0)throw new ApiError(409,"原资料已更新或下架");
        }
        return key;
    }
    /** Private uploads are stored outside the public /uploads resource mapping. */
    @PostMapping(value="/me/contribution-images",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    Object upload(@RequestParam("file")MultipartFile file,HttpServletRequest req)throws Exception{
        var u=auth.require(req);
        if(file.isEmpty()||file.getSize()>5*1024*1024)throw new ApiError(400,"请选择 5 MB 以内的 JPG 或 PNG 图片");
        BufferedImage original;
        try(var input=ImageIO.createImageInputStream(file.getInputStream())){
            Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new ApiError(400,"请选择 JPG 或 PNG 图片");
            var reader=readers.next();
            try{
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("jpg","jpeg","png").contains(format))throw new ApiError(400,"请选择 JPG 或 PNG 图片");
                reader.setInput(input,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||(long)width*height>30_000_000)throw new ApiError(400,"图片尺寸不能超过 30 MP");
                original=reader.read(0);
            }
            finally{
                reader.dispose();
            }
        }
        if(original==null)throw new ApiError(400,"图片无法读取，请换一张 JPG 或 PNG 图片");
        Path[] created={
            null};
        try{
            return db.transaction(tx->{
                db.update("UPDATE schema_migrations SET name=name WHERE name='admin-audit-v1'");
                if(db.count("SELECT count(*) FROM contribution_images WHERE owner_id=?",u.get("id"))>=100)throw new ApiError(400,"每个账号最多上传 100 张投稿图片");
                double scale=Math.min(1,1600.0/Math.max(original.getWidth(),original.getHeight()));
                int w=Math.max(1,(int)(original.getWidth()*scale)),h=Math.max(1,(int)(original.getHeight()*scale));
                BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
                Graphics2D g=image.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0,0,w,h);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(original,0,0,w,h,null);
                g.dispose();
                String key=UUID.randomUUID()+".jpg";
                Path dir=TravelApplication.DATA.resolve("contribution-uploads"),path=dir.resolve(key);
                try{
                    Files.createDirectories(dir);
                    created[0]=path;
                    if(!ImageIO.write(image,"jpg",path.toFile()))throw new IOException("JPEG writer unavailable");
                    db.update("INSERT INTO contribution_images(key,owner_id) VALUES(?,?)",key,u.get("id"));
                }
                catch(Exception e){
                    throw new ApiError(500,"图片保存失败");
                }
                return Map.of("url","/api/contribution-images/"+key);
            });
        }
        catch(RuntimeException e){
            if(created[0]!=null)try{
                Files.deleteIfExists(created[0]);
            }
            catch(Exception ignored){
            }
            throw e;
        }
    }
    @GetMapping("/contribution-images/{key}") ResponseEntity<Resource> image(@PathVariable String key,HttpServletRequest req){
        if(!key.matches("[0-9a-fA-F-]{36}\\.jpg"))throw new ApiError(404,"没有找到这张图片");
        var u=auth.require(req);
        var rows=db.list("SELECT owner_id FROM contribution_images WHERE key=?",key);
        if(rows.isEmpty()||(!"ADMIN".equals(u.get("role"))&&n(rows.get(0).get("owner_id"))!=n(u.get("id"))))throw new ApiError(404,"没有找到这张图片");
        Path p=TravelApplication.DATA.resolve("contribution-uploads").resolve(key);
        if(!Files.isRegularFile(p))throw new ApiError(404,"没有找到这张图片");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").body(new FileSystemResource(p));
    }
}
