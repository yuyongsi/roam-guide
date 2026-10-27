package cn.edu.travel;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 高德官方 URI 名称检索，不编造坐标，也不接收任意跳转网址。 */
final class SpotMaps {
    static void decorate(Map<String,Object> spot) {
        String query=String.valueOf(spot.getOrDefault("map_query","")).trim();
        if(query.isEmpty())query=spot.get("province")+" "+spot.get("city_name")+" "+spot.get("title");
        spot.put("map_query",query);
        spot.put("map_url","https://uri.amap.com/search?keyword="+encode(query)+"&city="+encode(String.valueOf(spot.get("city_name")))+"&view=map&src=roam-guide");
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
}
