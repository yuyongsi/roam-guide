package cn.edu.travel;

import java.util.*;
import org.springframework.core.io.ClassPathResource;

/** 只索引公开景点/攻略。触发器把源数据变动与待更新记录放在同一个事务。 */
public final class SearchIndex {
    private SearchIndex() {}
    public static void initialize(Database db) throws Exception {
        db.update("DROP TRIGGER IF EXISTS search_city_update");
        String sql=new ClassPathResource("search-triggers.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for(String statement:sql.split("(?m)(?=^CREATE TRIGGER)"))if(!statement.isBlank())db.update(statement);
        db.transaction(status->{
            db.update("UPDATE search_dirty SET item_id=item_id WHERE item_id=-1");
            if(db.count("SELECT count(*) FROM schema_migrations WHERE name='search-characters-v1'")==0){
                db.update("DELETE FROM search_fts");
                db.update("INSERT OR IGNORE INTO search_dirty(type,item_id) SELECT 'spot',id FROM spots");
                db.update("INSERT OR IGNORE INTO search_dirty(type,item_id) SELECT 'guide',id FROM guides");
                db.update("INSERT INTO schema_migrations(name) VALUES('search-characters-v1')");
            }
            flush(db);return null;
        });
    }
    public static String body(Database db,String type,Map<String,Object> row) {
        if(type.equals("spot"))return join(row,"description","highlights","transportation","tips");
        StringBuilder out=new StringBuilder(Objects.toString(row.get("subtitle"),""));
        Object decoded=db.decode(row.get("sections"));
        if(decoded instanceof List<?> sections)for(Object value:sections)if(value instanceof Map<?,?> section)
            out.append("\n").append(Objects.toString(section.get("title"),"")).append("\n").append(Objects.toString(section.get("body"),""));
        return out.toString();
    }
    private static String join(Map<String,Object> row,String... keys){var out=new ArrayList<String>();for(String key:keys)out.add(Objects.toString(row.get(key),""));return String.join("\n",out);}
    /** 调用方先取得 SQLite 写锁；更新和查询处于同一事务快照，避免旧索引命中新正文。 */
    static void flush(Database db) {
        for(var dirty:db.list("SELECT type,item_id FROM search_dirty")){
            String type=dirty.get("type").toString();Object id=dirty.get("item_id");String table=ApiController.table(type);
            db.update("DELETE FROM search_fts WHERE type=? AND item_id=?",type,id);
            var rows=db.list("SELECT a.*,c.name AS city_name,c.province FROM "+table+" a JOIN cities c ON c.id=a.city_id WHERE a.id=? AND a.published=1 AND c.published=1",id);
            if(!rows.isEmpty()){
                var r=rows.get(0);db.update("INSERT INTO search_fts(title,intro,body,location,type,item_id) VALUES(?,?,?,?,?,?)",
                    SearchText.index(r.get("title").toString()),SearchText.index(r.get("intro").toString()),
                    SearchText.index(body(db,type,r)),SearchText.index(r.get("city_name")+" "+r.get("province")),type,id);
            }
            db.update("DELETE FROM search_dirty WHERE type=? AND item_id=?",type,id);
        }
    }
}
