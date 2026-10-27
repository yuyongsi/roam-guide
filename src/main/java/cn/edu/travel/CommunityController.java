package cn.edu.travel;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 评论互动仅保存真实点赞；同一账号对同一评论只能有一条记录。 */
@RestController
@RequestMapping("/api")
public class CommunityController {
    final Database db;
    final Auth auth;
    public CommunityController(Database db,Auth auth){this.db=db;this.auth=auth;}

    @GetMapping("/guides/{id}/reactions")
    Object reactions(@PathVariable long id,HttpServletRequest req) {
        db.one(ApiController.publicLookup("guides"),id);
        var user=auth.current(req);Object uid=user==null?0:user.get("id");
        var rows=db.list("SELECT m.id AS comment_id,count(l.user_id) AS like_count,coalesce(max(CASE WHEN l.user_id=? THEN 1 ELSE 0 END),0) AS liked FROM comments m LEFT JOIN comment_likes l ON l.comment_id=m.id WHERE m.guide_id=? GROUP BY m.id ORDER BY m.id DESC",uid,id);
        for(var row:rows)row.put("liked",ApiController.number(row.get("liked"))==1);
        return Map.of("guide_id",id,"reactions",rows);
    }

    @PutMapping("/comments/{id}/like")
    Object like(@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);
        return db.transaction(status->{
            db.one("SELECT m.id FROM comments m JOIN guides g ON g.id=m.guide_id JOIN cities c ON c.id=g.city_id WHERE m.id=? AND g.published=1 AND c.published=1",id);
            db.update("INSERT OR IGNORE INTO comment_likes(comment_id,user_id) VALUES(?,?)",id,user.get("id"));
            return response(id,true);
        });
    }

    @DeleteMapping("/comments/{id}/like")
    Object unlike(@PathVariable long id,HttpServletRequest req) {
        var user=auth.require(req);
        return db.transaction(status->{
            db.one("SELECT id FROM comments WHERE id=?",id);
            db.update("DELETE FROM comment_likes WHERE comment_id=? AND user_id=?",id,user.get("id"));
            return response(id,false);
        });
    }

    private Map<String,Object> response(long id,boolean liked) {
        return Map.of("comment_id",id,"like_count",db.count("SELECT count(*) FROM comment_likes WHERE comment_id=?",id),"liked",liked);
    }
}
