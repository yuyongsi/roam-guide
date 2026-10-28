package cn.edu.travel;

import java.util.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.PlatformTransactionManager;

/** 所有 SQL 通过参数化查询执行；连接和事务由 Spring JDBC 管理。 */
@Repository
public class Database {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final TransactionTemplate tx;
    final DataSource source;
    public Database(DataSource source, ObjectMapper json, PlatformTransactionManager transactions) {
        this.source=source; this.jdbc=new JdbcTemplate(source); this.json=json; this.tx=new TransactionTemplate(transactions);
    }
    public List<Map<String,Object>> list(String sql, Object... args) { return jdbc.queryForList(sql,args); }
    public Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> rows=list(sql,args);
        if(rows.isEmpty()) throw new ApiError(404,"没有找到这条内容");
        return rows.get(0);
    }
    public int update(String sql, Object... args) { return jdbc.update(sql,args); }
    public <T> T transaction(TransactionCallback<T> callback) { return tx.execute(callback); }
    public long count(String sql, Object... args) { Long n=jdbc.queryForObject(sql,Long.class,args); return n==null?0:n; }
    public long insert(String sql, Object... args) {
        return tx.execute(status -> {
            jdbc.update(sql,args);
            return jdbc.queryForObject("SELECT last_insert_rowid()",Long.class);
        });
    }
    public String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception e){throw new ApiError(400,"内容格式不正确");}
    }
    public Object decode(Object value) {
        try{return json.readValue(String.valueOf(value),Object.class);}catch(Exception e){return List.of();}
    }
    public void initialize() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        // 投稿表是增量迁移；旧数据库及课程种子文件保持原样。
        new ResourceDatabasePopulator(new ClassPathResource("contributions.sql")).execute(source);
        // SQLite 触发器体内含分号，按完整 CREATE TRIGGER 语句执行。
        String triggers=new ClassPathResource("experience-triggers.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for(String statement:triggers.split("(?m)(?=^CREATE TRIGGER)"))if(!statement.isBlank())jdbc.execute(statement);
        {
            @SuppressWarnings("unchecked")
            Map<String,List<Map<String,Object>>> seed=json.readValue(new ClassPathResource("seed.json").getInputStream(),Map.class);
            tx.executeWithoutResult(status -> {
                for(var c:seed.get("cities")) update("INSERT OR IGNORE INTO cities(id,slug,name,province,tagline,description,image,sort_order) VALUES(?,?,?,?,?,?,?,?)",
                    c.get("id"),c.get("slug"),c.get("name"),c.get("province"),c.get("tagline"),c.get("description"),c.get("image"),c.get("sort_order"));
                for(var s:seed.get("spots")) update("INSERT OR IGNORE INTO spots(id,city_id,title,intro,description,highlights,transportation,tips,image,source_url) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    s.get("id"),s.get("city_id"),s.get("title"),s.get("intro"),s.get("description"),s.get("highlights"),s.get("transportation"),s.get("tips"),s.get("image"),s.get("source_url"));
                for(var g:seed.get("guides")) {
                    if(count("SELECT count(*) FROM guides WHERE id=?",g.get("id"))>0)continue;
                    update("INSERT INTO guides(id,city_id,title,subtitle,intro,image,sections,source_url) VALUES(?,?,?,?,?,?,?,?)",
                        g.get("id"),g.get("city_id"),g.get("title"),g.get("subtitle"),g.get("intro"),g.get("image"),encode(g.get("sections")),g.get("source_url"));
                    @SuppressWarnings("unchecked") List<Integer> ids=(List<Integer>)g.get("spot_ids");
                    int order=0;
                    for(Integer id:ids) update("INSERT INTO guide_spots(guide_id,spot_id,sort_order) VALUES(?,?,?)",g.get("id"),id,order++);
                }
            });
        }
        enrich();
        update("INSERT OR IGNORE INTO schema_migrations(name) VALUES('admin-audit-v1')");
        seedFoods();
        CityCatalog.migrate(this);
        if(count("SELECT count(*) FROM users WHERE role='ADMIN'")==0)
            update("INSERT INTO users(username,password_hash,role) VALUES(?,?,'ADMIN')","admin",Passwords.hash("TravelGuide!2026"));
        if(count("SELECT count(*) FROM users WHERE username='traveler'")==0)
            update("INSERT INTO users(username,password_hash) VALUES(?,?)","traveler",Passwords.hash("VisitChina!2026"));
        TripVersions.bootstrap(this);
        SearchIndex.initialize(this);
    }
    /** 可重复执行的升级；只有首次升级填充编辑建议，保留后续后台修改。 */
    private void enrich() throws Exception {
        column("cities","region_code","TEXT");
        column("cities","province_code","TEXT NOT NULL DEFAULT ''");
        column("cities","published","INTEGER NOT NULL DEFAULT 1");
        column("cities","revision","INTEGER NOT NULL DEFAULT 1");
        column("trip_budget_items","day_number","INTEGER NOT NULL DEFAULT 0");
        column("trip_budget_items","spot_id","INTEGER NOT NULL DEFAULT 0");
        column("shared_routes","source_revision","INTEGER NOT NULL DEFAULT 0");
        column("trips","checklist","TEXT NOT NULL DEFAULT '[]'");
        column("foods","revision","INTEGER NOT NULL DEFAULT 1");
        column("comments","updated_at","TEXT");
        for(String table:List.of("spots","guides")) {
            column(table,"revision","INTEGER NOT NULL DEFAULT 1");
            column(table,"theme","TEXT NOT NULL DEFAULT '人文历史'");
            column(table,"duration_minutes","INTEGER NOT NULL DEFAULT 120");
        }
        column("spots","map_query","TEXT NOT NULL DEFAULT ''");
        column("spots","gallery","TEXT NOT NULL DEFAULT '[]'");
        for(String field:List.of("food","travel_advice","visit_advice","food_source")) column("cities",field,"TEXT NOT NULL DEFAULT ''");
        if(count("SELECT count(*) FROM schema_migrations WHERE name='travel-features-v1'")>0)return;
        @SuppressWarnings("unchecked") Map<String,List<Map<String,Object>>> seed=json.readValue(new ClassPathResource("enrichment.json").getInputStream(),Map.class);
        transaction(status -> {
            for(var c:seed.get("cities"))update("UPDATE cities SET food=?,travel_advice=?,visit_advice=?,food_source=? WHERE id=?",c.get("food"),c.get("travel_advice"),c.get("visit_advice"),c.get("food_source"),c.get("id"));
            for(var s:seed.get("spots"))update("UPDATE spots SET theme=?,duration_minutes=?,gallery=? WHERE id=?",s.get("theme"),s.get("duration_minutes"),encode(s.get("gallery")),s.get("id"));
            for(var g:seed.get("guides"))update("UPDATE guides SET theme=?,duration_minutes=? WHERE id=?",g.get("theme"),g.get("duration_minutes"),g.get("id"));
            update("INSERT INTO schema_migrations(name) VALUES('travel-features-v1')");return null;
        });
    }
    private void seedFoods() throws Exception {
        @SuppressWarnings("unchecked") Map<String,List<Map<String,Object>>> seed=json.readValue(new ClassPathResource("food-seed.json").getInputStream(),Map.class);
        transaction(status->{
            for(var f:seed.get("foods"))update("INSERT OR IGNORE INTO foods(id,city_id,title,category,intro,description,taste,tips,image,source_url) VALUES(?,?,?,?,?,?,?,?,?,?)",f.get("id"),f.get("city_id"),f.get("title"),f.get("category"),f.get("intro"),f.get("description"),f.get("taste"),f.get("tips"),f.get("image"),f.get("source_url"));
            return null;
        });
    }
    private void column(String table,String name,String definition) {
        if(list("PRAGMA table_info("+table+")").stream().noneMatch(x->name.equals(x.get("name"))))update("ALTER TABLE "+table+" ADD COLUMN "+name+" "+definition);
    }
}
