package com.sqlfinder.chat.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** SQL过滤规则 */
public final class SqlPolicy {
    public static final String VERSION = "mysql-readonly-v1";
    public static final int MAX_SQL_LENGTH = 20_000;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_AST_NODES = 1_500;
    public static final long PARSE_TIMEOUT_MS = 2_000;
    public static final long MAX_DECLARED_LIMIT = 1_000;
    public static final long MAX_OFFSET = 100_000;

    public static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "ROUND", "ABS", "CEIL", "CEILING", "FLOOR",
            "COALESCE", "IFNULL", "NULLIF", "IF", "CONCAT", "CONCAT_WS", "LOWER", "UPPER",
            "LENGTH", "CHAR_LENGTH", "REPLACE", "SUBSTR", "SUBSTRING", "LEFT", "RIGHT",
            "YEAR", "MONTH", "DAY", "DAYOFMONTH", "DATE", "DATE_FORMAT", "DATEDIFF", "STR_TO_DATE",
            "NOW", "CURDATE", "CURTIME", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP"
    );
    public static final Set<String> ALLOWED_TIME_KEYS = Set.of(
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP"
    );
    public static final Set<String> IMPLICIT_IDENTITY_FUNCTIONS = Set.of(
            "CURRENT_USER", "SESSION_USER", "SYSTEM_USER", "CURRENT_ROLE"
    );

    private SqlPolicy() { }

    public static List<String> allowedFunctions() {
        return ALLOWED_FUNCTIONS.stream().sorted().toList();
    }

    public static Map<String, Object> describe() {
        return Map.of(
                "version", VERSION,
                "database", "MySQL",
                "allowedFunctions", allowedFunctions(),
                "supportedQueries", List.of("SELECT", "JOIN", "subquery", "UNION", "GROUP BY", "ORDER BY", "CASE"),
                "blockedFeatures", List.of("write statements", "metadata queries", "SELECT INTO", "locking reads",
                        "unlisted functions", "WITH", "window functions", "variables", "parameters", "comments",
                        "unsupported syntax", "double-quoted or backslash-escaped literals",
                        "dots or @ inside a single quoted identifier"),
                "maxSqlLength", MAX_SQL_LENGTH,
                "maxDepth", MAX_DEPTH,
                "maxDeclaredLimit", MAX_DECLARED_LIMIT,
                "executionEnabled", false
        );
    }
}
