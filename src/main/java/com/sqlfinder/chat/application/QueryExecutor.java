package com.sqlfinder.chat.application;

import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.chat.model.ValidatedSql;

/**
 * 查询执行接口：使用服务端认证身份重新核对表授权，不能仅信任候选摘要。
 */
public interface QueryExecutor {
    QueryResult execute(String userId, ValidatedSql candidate);
}
