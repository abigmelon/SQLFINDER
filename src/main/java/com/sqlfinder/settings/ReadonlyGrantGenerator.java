package com.sqlfinder.settings;

import org.springframework.stereotype.Service;
import java.util.HashSet;
import java.util.List;

/** 只读授权脚本生成 */
@Service
public class ReadonlyGrantGenerator {
    public String generate(Request request) {
        if (request==null || !DatabaseDiagnostics.identifier(request.catalog())
                || request.username()==null || !request.username().matches("[A-Za-z_][A-Za-z0-9_]{0,31}")
                || request.host()==null || !request.host().matches("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}")
                || request.tables()==null || request.tables().isEmpty() || request.tables().size()>50
                || request.tables().stream().anyMatch(t->!DatabaseDiagnostics.identifier(t))
                || new HashSet<>(request.tables()).size()!=request.tables().size()) {
            throw new ModelSettingsException(400,"INVALID_GRANT_REQUEST","请填写合法的库名、表名、账号和来源主机；不要使用通配符或引号。");
        }
        String account="'"+request.username()+"'@'"+request.host()+"'";
        StringBuilder sql=new StringBuilder("-- 在数据库管理工具中使用管理员账号执行；先替换密码占位符。\n")
                .append("-- 新建专用账号；如果账号已存在，请核对其旧权限，勿直接忽略创建错误。\n")
                .append("CREATE USER ").append(account).append(" IDENTIFIED BY 'REPLACE_WITH_A_RANDOM_STRONG_PASSWORD';\n");
        for (String table:request.tables()) sql.append("GRANT SELECT ON `").append(request.catalog())
                .append("`.`").append(table).append("` TO ").append(account).append(";\n");
        return sql.append("SHOW GRANTS FOR ").append(account).append(";\n").toString();
    }
    public record Request(String catalog,String username,String host,List<String> tables) { }
}
