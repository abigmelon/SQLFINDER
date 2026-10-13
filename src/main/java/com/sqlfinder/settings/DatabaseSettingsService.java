package com.sqlfinder.settings;

import com.sqlfinder.datasource.config.MetadataProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 数据库设置管理 */
@Service
public class DatabaseSettingsService {
    private final DatabaseConfiguration active;
    private DatabaseConfiguration saved;
    private final Path file;
    private final DatabaseConnectionTester tester;
    @Autowired public DatabaseSettingsService(Environment env, MetadataProperties metadata, DatabaseConnectionTester tester) {
        this(startup(env,metadata),DatabaseConfigFile.DEFAULT_PATH,tester);
    }
    DatabaseSettingsService(DatabaseConfiguration active, Path file, DatabaseConnectionTester tester) {
        this.active=active; this.file=file.toAbsolutePath().normalize(); this.tester=tester;
        if (Files.exists(this.file)) {
            try { saved=DatabaseConfigFile.read(this.file); }
            catch (Exception ex) { throw new IllegalStateException("已保存的数据库配置无法读取，请检查 config/database.properties。"); }
        }
    }
    public synchronized View view() {
        return new View(active==null?null:active.view(),saved==null?null:saved.view(),saved!=null && !saved.equals(active));
    }
    synchronized DatabaseConfiguration candidate(DatabaseSettingsRequest r) {
        if (r==null || r.port()==null || r.clearPassword() && r.password()!=null && !r.password().isEmpty()) throw DatabaseConfiguration.invalid();
        var base=saved==null?active:saved;
        String password=r.password();
        if (r.clearPassword()) password="";
        else if (password==null || password.isEmpty()) {
            if (base==null || !Objects.equals(base.host(),r.host()) || base.port()!=r.port()
                    || !Objects.equals(base.catalog(),r.catalog()) || !Objects.equals(base.username(),r.username()))
                throw new ModelSettingsException(400,"DATABASE_PASSWORD_REQUIRED","更换连接目标或账号时，请重新填写密码；无密码账号请明确勾选使用空密码。");
            password=base.password();
        }
        return new DatabaseConfiguration(r.host(),r.port(),r.catalog(),r.username(),password,r.requireTls(),r.tables()==null?List.of():r.tables());
    }
    public synchronized DatabaseConnectionTester.Result test(DatabaseSettingsRequest r) { return tester.inspect(candidate(r)); }
    public synchronized View save(DatabaseSettingsRequest r) {
        var next=candidate(r);
        if (next.tables().isEmpty()) throw DatabaseConfiguration.invalid();
        // Re-test the submitted connection and all selected tables; a prior browser test is not proof.
        var checked=tester.inspect(next);
        if (!"READ_ONLY".equals(checked.privileges())) throw new ModelSettingsException(400,"DATABASE_READONLY_REQUIRED",
                "连接未保存：请使用可确认只有读取权限的专用数据库账号；先核对直接授权和角色权限。");
        DatabaseConfigFile.write(file,next);
        saved=next;
        return view();
    }
    static DatabaseConfiguration startup(Environment env,MetadataProperties meta) {
        String url=env.getProperty("spring.datasource.hikari.jdbc-url",env.getProperty("spring.datasource.url",""));
        var matcher=Pattern.compile("jdbc:mysql://([A-Za-z0-9.-]+)(?::([0-9]+))?/([A-Za-z_][A-Za-z0-9_]{0,63})(?:\\?.*)?")
                .matcher(url);
        if (!matcher.matches() || !Objects.equals(matcher.group(3),meta.getCatalog())) return null;
        try {
            return new DatabaseConfiguration(matcher.group(1),matcher.group(2)==null?3306:Integer.parseInt(matcher.group(2)),
                    meta.getCatalog(),env.getProperty("spring.datasource.hikari.username",env.getProperty("spring.datasource.username","")),
                    env.getProperty("spring.datasource.hikari.password",env.getProperty("spring.datasource.password","")),
                    Pattern.compile("[?&](?:sslMode=(?:REQUIRED|VERIFY_CA|VERIFY_IDENTITY)|requireSSL=true)(?:&|$)",Pattern.CASE_INSENSITIVE).matcher(url).find(),
                    meta.getAllowedTables());
        } catch (IllegalArgumentException | ModelSettingsException ex) { return null; }
    }
    public record View(DatabaseConfiguration.View active,DatabaseConfiguration.View saved,boolean pendingRestart) { }
}
