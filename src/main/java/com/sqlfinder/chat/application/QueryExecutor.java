package com.sqlfinder.chat.application;

import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.chat.model.ValidatedSql;

/** 查询执行接口 */
public interface QueryExecutor {
    QueryResult execute(String userId, ValidatedSql candidate);
}
