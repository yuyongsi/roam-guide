package cn.edu.travel;

import java.util.*;

/** Derives public attribution from successful approvals and editorial audit history. */
final class PublicationMetadata {
    private PublicationMetadata(){}

    static Map<String,Object> forPublic(Database db,String kind,long itemId,Map<String,Object> canonical){
        String resource=switch(kind){case "spot"->"spots";case "guide"->"guides";case "food"->"foods";default->throw new ApiError(400,"内容类型不正确");};
        var created=db.list("SELECT u.username,c.reviewed_at FROM contributions c JOIN users u ON u.id=c.owner_id WHERE c.kind=? AND c.operation='CREATE' AND c.status='APPROVED' AND c.published_id=? AND c.reviewed_at IS NOT NULL ORDER BY c.reviewed_at,c.id LIMIT 1",kind,itemId);
        String author=created.isEmpty()?"漫游编辑整理":String.valueOf(created.get(0).get("username"));
        String authorKind=created.isEmpty()?"editorial":"user";
        String publishedAt=created.isEmpty()?null:nullableText(created.get(0).get("reviewed_at"));

        var userUpdates=db.list("SELECT u.username,c.reviewed_at FROM contributions c JOIN users u ON u.id=c.owner_id WHERE c.kind=? AND c.operation='UPDATE' AND c.status='APPROVED' AND c.published_id=? AND c.reviewed_at IS NOT NULL ORDER BY c.reviewed_at DESC,c.id DESC LIMIT 1",kind,itemId);
        var editorialUpdates=db.list("SELECT created_at FROM admin_audit_logs WHERE action='EDIT' AND resource=? AND target_id=? AND outcome='SUCCESS' ORDER BY created_at DESC,id DESC LIMIT 1",resource,String.valueOf(itemId));

        String updatedAt=null,lastContributor=null,lastKind=null;
        if(!userUpdates.isEmpty()){
            updatedAt=nullableText(userUpdates.get(0).get("reviewed_at"));
            lastContributor=nullableText(userUpdates.get(0).get("username"));
            lastKind="user";
        }
        if(!editorialUpdates.isEmpty()){
            String time=nullableText(editorialUpdates.get(0).get("created_at"));
            if(newer(time,updatedAt)){
                updatedAt=time;lastContributor="漫游编辑整理";lastKind="editorial";
            }
        }
        String createdAt=nullableText(canonical.get("created_at"));
        if(kind.equals("food")){
            String legacy=nullableText(canonical.get("updated_at"));
            if(newer(legacy,createdAt)&&newer(legacy,updatedAt)){
                updatedAt=legacy;lastContributor="漫游编辑整理";lastKind="editorial";
            }
        }

        Map<String,Object> result=new LinkedHashMap<>();
        result.put("author_name",author);
        result.put("author_kind",authorKind);
        result.put("recorded_at",createdAt);
        result.put("published_at",publishedAt);
        result.put("updated_at",updatedAt);
        result.put("last_contributor_name",lastContributor);
        result.put("last_update_kind",lastKind);
        result.put("has_updates",updatedAt!=null);
        return result;
    }

    static Map<String,Object> previewCreate(String username){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("author_name",username);
        result.put("author_kind","user");
        result.put("recorded_at",null);
        result.put("published_at",null);
        result.put("updated_at",null);
        result.put("last_contributor_name",null);
        result.put("last_update_kind",null);
        result.put("has_updates",false);
        result.put("preview_pending",true);
        return result;
    }

    static Map<String,Object> previewUpdate(Map<String,Object> published,String username){
        Map<String,Object> result=new LinkedHashMap<>(published);
        result.put("last_contributor_name",username);
        result.put("last_update_kind","user");
        result.put("updated_at",null);
        result.put("preview_pending",true);
        return result;
    }

    static Map<String,Object> pendingCreate(String username){
        return previewCreate(username);
    }

    private static String nullableText(Object value){return value==null?null:String.valueOf(value);}
    private static boolean newer(String left,String right){return left!=null&&(right==null||left.compareTo(right)>0);}
}
