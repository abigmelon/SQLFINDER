package com.sqlfinder.chat.model;


import java.util.List;
import java.util.Map;

public record QueryResult(
        List<String> columns,
        List<Map<String, Object>> rows,
        boolean truncated) //是否因为返回上限而省略了某些结果
{
}
