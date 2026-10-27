package cn.edu.travel;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 启动脚本只在数据库初始化结束后打开页面，不公开路径或用户信息。 */
@RestController
public class HealthController {
    static String instanceId(){
        String path=TravelApplication.DATA.toString();if(System.getProperty("os.name","").startsWith("Windows"))path=path.replace('/','\\').toLowerCase(Locale.ROOT);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("roam-guide\n"+path).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
    }
    @GetMapping("/api/health") Object health(HttpServletRequest req){
        if(!TravelApplication.ready)return ResponseEntity.status(503).body(Map.of("application","roam-guide","instance_id",instanceId(),"status",503,"code","SERVICE_STARTING","message","网站正在准备数据，请稍候","request_id",RequestTracking.id(req)));
        return Map.of("application","roam-guide","instance_id",instanceId(),"status","ready","database",DatabaseBootstrap.seeded?"initialized":"existing");
    }
}
