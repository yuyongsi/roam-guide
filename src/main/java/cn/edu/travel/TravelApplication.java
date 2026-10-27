package cn.edu.travel;

import java.nio.file.*;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootApplication
public class TravelApplication {
    public static final Path DATA = Paths.get(System.getenv().getOrDefault("ROAM_DATA_DIR", "data")).toAbsolutePath().normalize();
    static volatile boolean ready;
    public static void main(String[] args) { SpringApplication.run(TravelApplication.class, args); }

    @Bean DataSource dataSource() throws Exception {
        try{DatabaseBootstrap.prepare(DATA);}catch(DatabaseBootstrap.Problem e){org.slf4j.LoggerFactory.getLogger(TravelApplication.class).error("启动检查未通过：{}",e.getMessage());throw e;}
        Files.createDirectories(DATA.resolve("uploads"));
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + DATA.resolve("guide.db"));
        return source;
    }
    @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    @Bean CommandLineRunner initialize(Database database,@org.springframework.beans.factory.annotation.Value("${server.port:8818}") int port) {
        return args -> {
            try{database.initialize();ready=true;org.slf4j.LoggerFactory.getLogger(TravelApplication.class).info("漫游网站准备完成，请访问 http://127.0.0.1:{} （关闭启动窗口前请按 Ctrl+C 停止）",port);}
            catch(Exception e){org.slf4j.LoggerFactory.getLogger(TravelApplication.class).error("数据库升级未完成，请保留原文件并查看部署文档；异常类型={}",e.getClass().getName());throw e;}
        };
    }
}
