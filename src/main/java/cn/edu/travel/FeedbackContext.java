package cn.edu.travel;

import java.util.*;
import java.util.regex.*;
import static cn.edu.travel.ApiController.*;

/** Parses feedback references and resolves only currently public content. */
final class FeedbackContext {
    record Snapshot(String pagePath, String pageTitle, String targetExcerpt) {}
    private static final Pattern SPOT=Pattern.compile("^/spot/([1-9][0-9]{0,17})$");
    private static final Pattern GUIDE=Pattern.compile("^/guide/([1-9][0-9]{0,17})(?:#comment-([1-9][0-9]{0,17}))?$");
    private static final Pattern DEST=Pattern.compile("^/destination/([a-z0-9-]+)$");
    private static final Pattern FOOD=Pattern.compile("^/food/([1-9][0-9]{0,17})$");
    private static final Pattern POST=Pattern.compile("^/community/post/([1-9][0-9]{0,17})(?:#comment-([1-9][0-9]{0,17}))?$");
    private static final Pattern ROUTE=Pattern.compile("^/routes/([1-9][0-9]{0,17})$");
    private static final Set<String> GENERAL=Set.of("/","/destinations","/guides","/food","/community","/routes","/search","/recommend","/trips","/me");
    private final Database db;
    FeedbackContext(Database db){this.db=db;}

    Snapshot resolve(String raw) {
        if(raw==null||raw.isEmpty()||raw.length()>200||raw.charAt(0)!='/'||raw.startsWith("//")||raw.indexOf('\\')>=0||raw.indexOf('%')>=0||raw.indexOf('?')>=0||raw.indexOf('\0')>=0)
            throw invalid();
        if(GENERAL.contains(raw))return new Snapshot(raw,generalTitle(raw),"");
        Matcher m=SPOT.matcher(raw);
        if(m.matches()) {var r=db.one("SELECT s.title FROM spots s JOIN cities c ON c.id=s.city_id WHERE s.id=? AND s.published=1 AND c.published=1",id(m.group(1)));return new Snapshot(raw,"景点 · "+r.get("title"),"");}
        m=GUIDE.matcher(raw);
        if(m.matches()) {
            long id=id(m.group(1));var g=db.one("SELECT g.title FROM guides g JOIN cities c ON c.id=g.city_id WHERE g.id=? AND g.published=1 AND c.published=1",id);
            String excerpt="";
            if(m.group(2)!=null){var c=db.one("SELECT body FROM comments WHERE id=? AND guide_id=?",id(m.group(2)),id);excerpt=excerpt(c.get("body"));}
            return new Snapshot(raw,"攻略 · "+g.get("title"),excerpt);
        }
        m=DEST.matcher(raw);
        if(m.matches()){var r=db.one("SELECT name FROM cities WHERE slug=? AND published=1",m.group(1));return new Snapshot(raw,"目的地 · "+r.get("name"),"");}
        m=FOOD.matcher(raw);
        if(m.matches()){var r=db.one("SELECT f.title FROM foods f JOIN cities c ON c.id=f.city_id WHERE f.id=? AND f.published=1 AND c.published=1",id(m.group(1)));return new Snapshot(raw,"美食 · "+r.get("title"),"");}
        m=POST.matcher(raw);
        if(m.matches()){
            long id=id(m.group(1));var p=db.one("SELECT title FROM social_posts WHERE id=? AND status='PUBLISHED'",id);String excerpt="";
            if(m.group(2)!=null){var c=db.one("SELECT body FROM social_comments WHERE id=? AND post_id=?",id(m.group(2)),id);excerpt=excerpt(c.get("body"));}
            return new Snapshot(raw,"帖子 · "+p.get("title"),excerpt);
        }
        m=ROUTE.matcher(raw);
        if(m.matches()){var r=db.one("SELECT title FROM shared_routes WHERE id=? AND status='PUBLISHED' AND blocked=0",id(m.group(1)));return new Snapshot(raw,"公开路线 · "+r.get("title"),"");}
        throw invalid();
    }
    boolean available(String path){try{resolve(path);return true;}catch(ApiError e){if(e.status==400||e.status==404)return false;throw e;}}
    static String excerpt(Object body){String s=Objects.toString(body,"").trim();if(s.length()<=200)return s;int end=200;if(Character.isHighSurrogate(s.charAt(end-1))&&Character.isLowSurrogate(s.charAt(end)))end--;return s.substring(0,end);}
    private static long id(String value){try{return Long.parseLong(value);}catch(NumberFormatException e){throw invalid();}}
    static String legacyTitle(String path){
        if(path==null||path.isBlank())return "";
        try {
            if(GENERAL.contains(path))return generalTitle(path);
            if(SPOT.matcher(path).matches())return "景点页面";
            if(GUIDE.matcher(path).matches())return "旅行攻略";
            if(DEST.matcher(path).matches())return "目的地页面";
            if(FOOD.matcher(path).matches())return "美食页面";
            if(POST.matcher(path).matches())return "社区帖子";
            if(ROUTE.matcher(path).matches())return "公开路线";
        }catch(Exception ignored){}
        return "";
    }
    private static String generalTitle(String path){return switch(path){case "/"->"漫游首页";case "/destinations"->"目的地";case "/guides"->"旅行攻略";case "/food"->"美食探索";case "/community"->"旅行社区";case "/routes"->"路线广场";case "/search"->"站内搜索";case "/recommend"->"旅行推荐";case "/trips"->"我的行程";default->"个人中心";};}
    private static ApiError invalid(){return new ApiError(400,"相关页面路径不合法或暂不支持");}
}
