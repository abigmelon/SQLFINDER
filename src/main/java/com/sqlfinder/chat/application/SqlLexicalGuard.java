package com.sqlfinder.chat.application;

/** SQL文本检查 */
final class SqlLexicalGuard {
    private SqlLexicalGuard() { }

    static void check(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlValidationException("SQL_EMPTY", "SQL 不能为空。");
        }
        if (sql.length() > SqlPolicy.MAX_SQL_LENGTH) {
            throw new SqlValidationException("SQL_TOO_LONG", "SQL 超出允许长度。");
        }
        char quote = 0;
        int quoteStart = 0;
        int parentheses = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                throw new SqlValidationException("SQL_INVALID_CHARACTER", "SQL 包含不支持的控制字符。");
            }
            if (c == '\\') {
                throw new SqlValidationException("SQL_UNSUPPORTED_ESCAPE", "不支持反斜杠转义，请使用 SQL 的重复引号转义。");
            }
            if (quote != 0) {
                if (c == quote) {
                    if (next == quote) {
                        i++;
                    } else {
                        if (quote == '`') {
                            String identifier = sql.substring(quoteStart + 1, i);
                            if (identifier.indexOf('.') >= 0 || identifier.indexOf('@') >= 0) {
                                throw new SqlValidationException("SQL_UNSUPPORTED_IDENTIFIER",
                                        "单个反引号标识符中暂不支持点号或 @ 字符。");
                            }
                        }
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '`') {
                quote = c;
                quoteStart = i;
            } else if (c == '"') {
                throw new SqlValidationException("SQL_UNSUPPORTED_QUOTES", "字符串请使用单引号，标识符请使用反引号。");
            } else if (c == '#' || (c == '-' && next == '-') || (c == '/' && next == '*')) {
                throw new SqlValidationException("SQL_COMMENT_NOT_ALLOWED", "当前不允许 SQL 注释或可执行注释。");
            } else if (c == '@') {
                throw new SqlValidationException("SQL_VARIABLE_NOT_ALLOWED", "不允许用户变量或系统变量。");
            } else if (c == '(') {
                if (++parentheses > SqlPolicy.MAX_DEPTH) {
                    throw new SqlValidationException("SQL_TOO_COMPLEX", "SQL 嵌套层数超出限制。");
                }
            } else if (c == ')') {
                if (--parentheses < 0) {
                    throw new SqlValidationException("SQL_MALFORMED", "SQL 括号不匹配。");
                }
            }
        }
        if (quote != 0 || parentheses != 0) {
            throw new SqlValidationException("SQL_MALFORMED", "SQL 引号或括号不匹配。");
        }
    }
}
