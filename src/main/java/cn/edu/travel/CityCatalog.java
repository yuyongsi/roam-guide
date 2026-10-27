package cn.edu.travel;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import static cn.edu.travel.ApiController.*;

/** 项目内置的省/市级目录，不包含区县，也不在运行时依赖外部接口。 */
@Component
public class CityCatalog {
    private final Map<String,Object> metadata;
    private final List<Map<String,Object>> provinces;
    private final Map<String,Map<String,Object>> regions=new LinkedHashMap<>();
    @SuppressWarnings("unchecked")
    public CityCatalog(ObjectMapper json) throws Exception {
        metadata=json.readValue(new ClassPathResource("city-catalog.json").getInputStream(),Map.class);
        provinces=(List<Map<String,Object>>)metadata.get("provinces");
        for(var province:provinces)for(var city:(List<Map<String,Object>>)province.get("cities")){
            var row=new LinkedHashMap<>(city);row.put("province_code",province.get("code"));row.put("province_name",province.get("name"));
            if(regions.put(city.get("code").toString(),Map.copyOf(row))!=null)throw new IllegalStateException("市级目录编码重复");
        }
    }
    public Map<String,Object> region(String province,String code){
        var row=regions.get(code);
        if(row==null||!row.get("province_code").equals(province))throw new ApiError(400,"请从所选省份的市级目录中选择城市，暂不支持区县");
        return row;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> options(Database db){
        Map<String,Map<String,Object>> existing=new HashMap<>();
        for(var c:db.list("SELECT id,name,region_code,published FROM cities WHERE region_code IS NOT NULL"))existing.put(c.get("region_code").toString(),c);
        var out=new ArrayList<Map<String,Object>>();
        for(var p:provinces){var cities=new ArrayList<Map<String,Object>>();
            for(var c:(List<Map<String,Object>>)p.get("cities")){
                var row=new LinkedHashMap<>(c);var saved=existing.get(c.get("code").toString());
                row.put("existing",saved!=null);
                if(saved!=null){row.put("existing_id",saved.get("id"));row.put("existing_name",saved.get("name"));row.put("existing_published",saved.get("published"));}
                cities.add(row);
            }
            out.add(Map.of("code",p.get("code"),"name",p.get("name"),"cities",cities));
        }
        return Map.of("version",metadata.get("version"),"scope",metadata.get("scope"),"provinces",out);
    }
    public static void migrate(Database db){
        db.transaction(status->{
            // 仅为原有五个目的地附加标准标识；保留其编号、简称、网址和内容。
            for(var mapping:List.of(new String[]{"1","杭州","330100"},new String[]{"2","成都","510100"},new String[]{"3","南阳","411300"},new String[]{"4","泉州","350500"},new String[]{"5","大理","532900"}))
                db.update("UPDATE cities SET region_code=?,province_code=? WHERE id=? AND name IN (?,?) AND region_code IS NULL",mapping[2],mapping[2].substring(0,2)+"0000",Long.parseLong(mapping[0]),mapping[1],mapping[1]+"市");
            db.update("CREATE UNIQUE INDEX IF NOT EXISTS idx_cities_region_code ON cities(region_code)");return null;
        });
    }
}
