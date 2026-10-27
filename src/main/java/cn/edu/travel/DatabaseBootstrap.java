package cn.edu.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.sqlite.SQLiteConfig;

/** 首次启动装入校验过的课程演示库；绝不替换已经存在的数据库。 */
final class DatabaseBootstrap {
    private static FileChannel lockChannel;
    private static FileLock dataLock;
    static boolean seeded;

    static void prepare(Path directory) {
        Path staged=null;
        try {
            Files.createDirectories(directory);
            lockChannel=FileChannel.open(directory.resolve(".startup.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            try { dataLock=lockChannel.tryLock(); } catch(OverlappingFileLockException e) { dataLock=null; }
            if(dataLock==null)throw new Problem("此数据目录正在被另一个网站进程使用。请先关闭原来的启动窗口，或使用其他数据目录。");
            Runtime.getRuntime().addShutdownHook(new Thread(DatabaseBootstrap::release,"roam-data-lock"));
            Path destination=directory.resolve("guide.db");
            if(Files.exists(destination)) {
                validate(destination,false,null);
                LoggerFactory.getLogger(DatabaseBootstrap.class).info("已有数据库检查通过，继续使用；不会导入或覆盖演示数据。");
                return;
            }
            var mapper=new ObjectMapper();
            @SuppressWarnings("unchecked") Map<String,Object> manifest=mapper.readValue(new ClassPathResource("bootstrap/demo-manifest.json").getInputStream(),Map.class);
            if(!"roam-guide".equals(manifest.get("application")))throw new Problem("演示数据清单不正确，请重新解压完整源码；不会创建空白数据库。");
            staged=directory.resolve(".guide-bootstrap-"+UUID.randomUUID()+".tmp");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new ClassPathResource("bootstrap/guide.db").getInputStream();OutputStream out=Files.newOutputStream(staged,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[65536];int length;
                while((length=in.read(buffer))!=-1){out.write(buffer,0,length);digest.update(buffer,0,length);}
            }
            if(!HexFormat.of().formatHex(digest.digest()).equals(manifest.get("sha256")))throw new Problem("演示数据库校验失败，请重新解压完整源码；原数据不会被覆盖。");
            validate(staged,true,manifest);
            // 同目录移动且不使用 REPLACE_EXISTING，目标即使被其他程序创建也不覆盖。
            Files.move(staged,destination);
            staged=null;seeded=true;
            LoggerFactory.getLogger(DatabaseBootstrap.class).info("首次启动已装入完整课程演示数据，版本={}",manifest.get("dataset_version"));
        } catch(Problem e) { release();throw e; }
        catch(Exception e) { release();throw new Problem("数据准备未完成。请确认目录可写、源码完整，并保留已有数据库；详细操作见部署文档。",e); }
        finally {if(staged!=null)try{Files.deleteIfExists(staged);}catch(IOException ignored){/* 仅清理本次生成的临时文件。 */}}
    }
    private static void validate(Path file,boolean demo,Map<String,Object> manifest) throws Exception {
        if(!Files.isRegularFile(file)||Files.size(file)<100)throw new Problem("已有数据库为空或不是普通文件。为避免误覆盖，启动已停止；请先备份，再按部署文档处理。");
        try(InputStream in=Files.newInputStream(file)) {
            if(!Arrays.equals(in.readNBytes(16),"SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new Problem("数据库格式不正确，启动已停止且不会重置。请保留原文件并检查数据目录。");
        }
        SQLiteConfig config=new SQLiteConfig();config.setReadOnly(true);config.setBusyTimeout(5000);
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+file,config.toProperties());Statement statement=connection.createStatement()) {
            try(ResultSet rows=statement.executeQuery("PRAGMA quick_check")){if(!rows.next()||!"ok".equals(rows.getString(1)))throw new Problem("数据库完整性检查未通过，启动已停止。请保留原文件并从备份恢复，不要直接覆盖。");}
            for(String table:List.of("users","cities","spots"))try(PreparedStatement query=connection.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?")){query.setString(1,table);try(ResultSet rows=query.executeQuery()){if(!rows.next()||rows.getInt(1)!=1)throw new Problem("当前文件不是本网站的有效数据库。请检查数据目录，原文件未被覆盖。");}}
            try(ResultSet rows=statement.executeQuery("PRAGMA foreign_key_check")){if(rows.next())throw new Problem("数据库关联检查未通过，启动已停止；请先备份并检查原数据。");}
            if(demo) {
                @SuppressWarnings("unchecked") Map<String,Number> counts=(Map<String,Number>)manifest.get("counts");
                for(String table:List.of("cities","spots","guides","foods","users","comments","social_posts","social_comments","shared_routes","trips","admin_audit_logs"))try(ResultSet rows=statement.executeQuery("SELECT count(*) FROM "+table)){if(!rows.next()||!counts.containsKey(table)||rows.getLong(1)!=counts.get(table).longValue())throw new Problem("演示数据库内容不完整，请重新解压；不会退回空白网站。");}
            }
        } catch(SQLException e) {throw new Problem("数据库无法读取或正在被其他程序占用。启动已停止，原文件未被替换；请检查权限和数据库备份。",e);}
    }
    private static void release(){try{if(dataLock!=null&&dataLock.isValid())dataLock.release();}catch(IOException ignored){}try{if(lockChannel!=null)lockChannel.close();}catch(IOException ignored){}}
    static final class Problem extends IllegalStateException {Problem(String message){super(message);}Problem(String message,Throwable cause){super(message,cause);}}
}
