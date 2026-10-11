package com.sqlfinder.chat.application;

import com.sqlfinder.chat.model.ValidatedSql;
import com.sqlfinder.datasource.model.TableSchema;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SQL校验入口
 */
@Component
public class SqlValidator {

    /** Parse the entire input; never salvage the first statement or strip unsafe content. */
    public Select parseSingleSelect(String sql) {
        SqlLexicalGuard.check(sql);
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql, parser -> parser
                    .withTimeOut(SqlPolicy.PARSE_TIMEOUT_MS)
                    .withUnsupportedStatements(false)
                    .withErrorRecovery(false));
        } catch (JSQLParserException | RuntimeException e) {
            throw new SqlValidationException("SQL_MALFORMED", "SQL 语法无法完整解析。", e);
        }
        if (statements == null || statements.size() != 1) {
            throw new SqlValidationException("SQL_MULTIPLE_STATEMENTS", "只允许一条 SQL 语句。");
        }
        Statement statement = statements.get(0);
        if (!(statement instanceof Select select)) {
            throw new SqlValidationException("SQL_READ_ONLY", "只允许 SELECT 查询。");
        }
        return select;
    }

    public ValidatedSql validate(String sql, List<TableSchema> authorizedSchemas) {
        Select select = parseSingleSelect(sql);
        return new ValidatedSql(sql.strip(), new SqlAstGuard(authorizedSchemas).check(select));
    }

    /** Retained for the existing learning examples; now applies the whole query policy. */
    public void checkTableAccess(String sql, List<TableSchema> authorizedSchemas) {
        validate(sql, authorizedSchemas);
    }
}
