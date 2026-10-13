package com.sqlfinder.chat.model;


import java.util.List;
import java.util.Map;

/** 数据库查询结果 */
public record QueryResult(
        List<String> columns,
        List<Map<String, Object>> rows,
        boolean truncated,
        List<ResultColumn> columnMetadata)
{
    public QueryResult(List<String> columns,List<Map<String,Object>> rows,boolean truncated) {
        this(columns,rows,truncated,List.of());
    }
}
