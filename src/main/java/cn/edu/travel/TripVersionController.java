package cn.edu.travel;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static cn.edu.travel.ApiController.*;

/** 历史读取与恢复均验证本人身份，管理员不享有私人行程读取权限。 */
@RestController
@RequestMapping("/api/me/trips/{tripId}/versions")
public class TripVersionController {
    final Database db;final Auth auth;final FeatureController trips;
    public TripVersionController(Database db,Auth auth,FeatureController trips){this.db=db;this.auth=auth;this.trips=trips;}
    Map<String,Object> owned(long id,HttpServletRequest req){return db.one("SELECT * FROM trips WHERE id=? AND user_id=?",id,auth.require(req).get("id"));}
    Map<String,Object> version(long id,long version){if(version<1)throw new ApiError(404,"没有这个行程版本");return db.one("SELECT version_number,trip_revision,schedule_revision,kind,restored_from,summary,created_at,snapshot FROM trip_versions WHERE trip_id=? AND version_number=?",id,version);}
    @GetMapping Object list(@PathVariable long tripId,@RequestParam(defaultValue="1") int page,HttpServletRequest req){return db.transaction(status->{var t=owned(tripId,req);long current=TripVersions.head(db,tripId),total=db.count("SELECT count(*) FROM trip_versions WHERE trip_id=?",tripId);int p=Math.max(1,Math.min(100000,page));var rows=db.list("SELECT version_number,kind,restored_from,summary,created_at FROM trip_versions WHERE trip_id=? ORDER BY version_number DESC LIMIT 12 OFFSET ?",tripId,(p-1)*12);for(var r:rows)r.put("current",number(r.get("version_number"))==current);return Map.of("items",rows,"total",total,"page",p,"size",12,"current_version",current,"trip_revision",t.get("revision"),"schedule_revision",TripVersions.scheduleRevision(db,tripId));});}
    @GetMapping("/compare") Object compare(@PathVariable long tripId,@RequestParam long from,@RequestParam long to,HttpServletRequest req){return db.transaction(status->{owned(tripId,req);var a=TripVersions.read(db,version(tripId,from).get("snapshot"));var b=TripVersions.read(db,version(tripId,to).get("snapshot"));var changes=TripVersions.diff(a,b);return Map.of("from_version",from,"to_version",to,"changes",changes,"total_changes",changes.size());});}
    @GetMapping("/{number}") Object detail(@PathVariable long tripId,@PathVariable("number") long version,HttpServletRequest req){return db.transaction(status->{owned(tripId,req);var row=version(tripId,version);row.put("snapshot",TripVersions.read(db,row.get("snapshot")));row.put("current",version==TripVersions.head(db,tripId));return row;});}
    @PostMapping("/{number}/restore") Object restore(@PathVariable long tripId,@PathVariable("number") long version,@RequestBody Map<String,Object> body,HttpServletRequest req){return db.transaction(status->{
        var current=owned(tripId,req);var selected=version(tripId,version);long head=TripVersions.head(db,tripId),schedule=TripVersions.scheduleRevision(db,tripId);
        if(number(body.get("trip_revision"))!=number(current.get("revision"))||number(body.get("schedule_revision"))!=schedule||number(body.get("history_version"))!=head)throw new ApiError(409,"行程或时间已在其他窗口更新，请重新查看历史后再恢复");
        if(head==version)throw new ApiError(409,"这已经是当前版本，无需恢复");
        var snapshot=TripVersions.read(db,selected.get("snapshot"));var payload=new LinkedHashMap<>(snapshot);payload.put("revision",current.get("revision"));
        // 使用原行程校验，历史中已下架且当前已移除的景点将整份拒绝恢复。
        trips.save(tripId,payload,true);
        String data=db.encode(snapshot.get("schedule"));int changed;
        if(schedule==0)changed=db.update("INSERT OR IGNORE INTO trip_day_schedules(trip_id,settings) VALUES(?,?)",tripId,data);
        else changed=db.update("UPDATE trip_day_schedules SET settings=?,revision=revision+1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE trip_id=? AND revision=?",data,tripId,schedule);
        if(changed==0)throw new ApiError(409,"时间安排已经更新，请刷新后重试");
        long next=TripVersions.capture(db,tripId,"RESTORE",version);
        return Map.of("message","已恢复版本 "+version+" 的行程和时间安排，并保存为新版本 "+next,"version_number",next);
    });}
}
