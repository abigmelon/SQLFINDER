package com.sqlfinder.chat.application;

import com.sqlfinder.chat.model.SqlValidationSummary;
import com.sqlfinder.datasource.model.TableSchema;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.*;
import net.sf.jsqlparser.expression.operators.conditional.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * SQL语法树检查
 */
final class SqlAstGuard {
    private static final Set<Class<?>> BINARY_OPERATORS = Set.of(
            Addition.class, Subtraction.class, Multiplication.class, Division.class,
            IntegerDivision.class, Modulo.class, AndExpression.class, OrExpression.class,
            XorExpression.class, EqualsTo.class, NotEqualsTo.class, GreaterThan.class,
            GreaterThanEquals.class, MinorThan.class, MinorThanEquals.class
    );
    private static final Set<Class<?>> LITERALS = Set.of(
            StringValue.class, LongValue.class, DoubleValue.class, NullValue.class,
            DateValue.class, TimeValue.class, TimestampValue.class, HexValue.class
    );

    private record TableKey(String catalog, String name) { }

    private final Set<TableKey> allowedTables = new HashSet<>();
    private final Set<String> tables = new TreeSet<>();
    private final Set<String> functions = new TreeSet<>();
    private final String defaultCatalog;
    private int nodes;

    SqlAstGuard(List<TableSchema> schemas) {
        if (schemas == null || schemas.isEmpty()) {
            throw failure("SQL_NO_AUTHORIZED_TABLES", "授权表结构不能为空。");
        }
        Set<String> catalogs = new HashSet<>();
        for (TableSchema schema : schemas) {
            if (schema == null || schema.catalog() == null || schema.catalog().isBlank()
                    || schema.tableName() == null || schema.tableName().isBlank()) {
                throw failure("SQL_INVALID_SCHEMA", "授权表必须包含数据库名和表名。");
            }
            catalogs.add(schema.catalog());
            allowedTables.add(new TableKey(schema.catalog(), schema.tableName()));
        }
        if (catalogs.size() != 1) {
            throw failure("SQL_INVALID_SCHEMA", "当前只支持一个授权数据库。");
        }
        defaultCatalog = catalogs.iterator().next();
    }

    SqlValidationSummary check(Select select) {
        select(select, 0);
        if (tables.isEmpty()) {
            throw failure("SQL_NO_TABLE", "查询必须引用至少一张授权业务表。");
        }
        return new SqlValidationSummary(SqlPolicy.VERSION, List.copyOf(tables), List.copyOf(functions));
    }

    private void select(Select query, int depth) {
        tick(depth);
        if (query == null) {
            throw failure("SQL_MALFORMED", "查询结构不完整。");
        }
        if (nonEmpty(query.getWithItemsList())) {
            throw failure("SQL_UNSUPPORTED_SYNTAX", "当前暂不支持 WITH 查询。");
        }
        if (query.getForMode() != null || query.getForUpdateTable() != null
                || query.getForClause() != null || query.isNoWait() || query.isSkipLocked()
                || query.getWait() != null || query.getIsolation() != null) {
            throw failure("SQL_READ_ONLY", "不允许锁定查询或 FOR 子句。");
        }
        if (query.getPivot() != null || query.getUnPivot() != null
                || query.isOracleSiblings() || query.getLimitBy() != null || query.getFetch() != null) {
            unsupported(query);
        }

        if (query instanceof PlainSelect plain) {
            plain(plain, depth + 1);
        } else if (query instanceof ParenthesedSelect parenthesed) {
            if (parenthesed.getSampleClause() != null) {
                unsupported(parenthesed);
            }
            select(parenthesed.getSelect(), depth + 1);
        } else if (query instanceof SetOperationList union) {
            if (union.getSampleClause() != null || !nonEmpty(union.getSelects())
                    || !nonEmpty(union.getOperations())
                    || union.getOperations().size() != union.getSelects().size() - 1) {
                unsupported(union);
            }
            for (SetOperation operation : union.getOperations()) {
                if (!(operation instanceof UnionOp)) {
                    unsupported(operation);
                }
            }
            for (Select branch : union.getSelects()) {
                select(branch, depth + 1);
            }
        } else {
            unsupported(query);
        }

        // These properties can occur on plain SELECT, UNION, and parenthesised wrappers.
        if (query.getOrderByElements() != null) {
            for (OrderByElement order : query.getOrderByElements()) {
                expression(order.getExpression(), depth + 1);
            }
        }
        if (query.getLimit() != null) {
            Limit limit = query.getLimit();
            if (limit.getRowCount() instanceof AllValue || limit.getRowCount() instanceof NullValue
                    || limit.getByExpressions() != null) {
                unsupported(limit);
            }
            boundedInteger(limit.getRowCount(), SqlPolicy.MAX_DECLARED_LIMIT, "LIMIT");
            if (limit.getOffset() != null) {
                boundedInteger(limit.getOffset(), SqlPolicy.MAX_OFFSET, "OFFSET");
            }
        }
        if (query.getOffset() != null) {
            boundedInteger(query.getOffset().getOffset(), SqlPolicy.MAX_OFFSET, "OFFSET");
            if (query.getOffset().getOffsetParam() != null) {
                unsupported(query.getOffset());
            }
        }
    }

    private void plain(PlainSelect query, int depth) {
        if (nonEmpty(query.getIntoTables()) || query.getIntoTempTable() != null) {
            throw failure("SQL_READ_ONLY", "不允许 SELECT INTO 或导出操作。");
        }
        // Reject dialect extensions and hidden expression containers rather than ignoring them.
        if (nonEmpty(query.getLateralViews()) || query.getSampleClause() != null
                || query.getOptimizeFor() != null || query.getTop() != null
                || query.getSkip() != null || query.getFirst() != null
                || query.getQualify() != null || query.getOracleHierarchical() != null
                || query.getPreferringClause() != null || query.getOracleHint() != null
                || query.getForXmlPath() != null || query.getKsqlWindow() != null
                || nonEmpty(query.getWindowDefinitions()) || query.isEmitChanges()
                || query.isUseWithNoLog() || query.isUsingFinal() || query.isUsingOnly()
                || query.getBigQuerySelectQualifier() != null || query.getMySqlHintStraightJoin()
                || query.getMySqlSqlCalcFoundRows() || query.getMySqlSqlCacheFlag() != null) {
            unsupported(query);
        }
        if (query.getDistinct() != null && (nonEmpty(query.getDistinct().getOnSelectItems())
                || query.getDistinct().isUseUnique())) {
            unsupported(query.getDistinct());
        }
        from(query.getFromItem(), depth + 1);
        if (query.getJoins() != null) {
            for (Join join : query.getJoins()) {
                if (join.isApply() || join.isSemi() || join.isGlobal() || join.isWindowJoin()
                        || join.getJoinWindow() != null || join.getJoinHint() != null
                        || join.isFull() || join.isStraight()) {
                    unsupported(join);
                }
                from(join.getRightItem(), depth + 1);
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        expression(on, depth + 1);
                    }
                }
                if (join.getUsingColumns() != null) {
                    for (Column column : join.getUsingColumns()) {
                        expression(column, depth + 1);
                    }
                }
            }
        }
        if (!nonEmpty(query.getSelectItems())) {
            throw failure("SQL_MALFORMED", "查询没有返回表达式。");
        }
        for (SelectItem<?> item : query.getSelectItems()) {
            expression(item.getExpression(), depth + 1);
        }
        expression(query.getWhere(), depth + 1);
        expression(query.getHaving(), depth + 1);
        if (query.getGroupBy() != null) {
            if (nonEmpty(query.getGroupBy().getGroupingSets()) || query.getGroupBy().isMysqlWithRollup()) {
                unsupported(query.getGroupBy());
            }
            expression(query.getGroupBy().getGroupByExpressionList(), depth + 1);
        }
    }

    private void from(FromItem source, int depth) {
        if (source == null) {
            return;
        }
        tick(depth);
        if (source instanceof Table table) {
            if (table.getNameParts().size() > 2 || table.getNameParts().isEmpty()
                    || table.getPivot() != null || table.getUnPivot() != null
                    || table.getIndexHint() != null || table.getSqlServerHints() != null
                    || table.getSampleClause() != null) {
                unsupported(table);
            }
            String name = identifier(table.getName());
            String catalog = table.getSchemaName() == null
                    ? defaultCatalog : identifier(table.getSchemaName());
            if (!allowedTables.contains(new TableKey(catalog, name))) {
                throw failure("SQL_UNAUTHORIZED_TABLE", "SQL 引用了未授权的表：" + catalog + "." + name);
            }
            tables.add(catalog + "." + name);
        } else if (source instanceof ParenthesedSelect subquery) {
            select(subquery, depth + 1);
        } else {
            unsupported(source);
        }
    }

    private void expression(Expression value, int depth) {
        if (value == null) {
            return;
        }
        tick(depth);
        if (LITERALS.contains(value.getClass())) {
            return;
        }
        if (value instanceof Column column) {
            // MySQL recognises bare CURRENT_USER as a function; JSqlParser models it as a column.
            boolean unqualified = column.getTable() == null || column.getTable().getName() == null;
            String name = column.getColumnName().toUpperCase(Locale.ROOT);
            if (unqualified && SqlPolicy.IMPLICIT_IDENTITY_FUNCTIONS.contains(name)) {
                throw failure("SQL_FUNCTION_NOT_ALLOWED", "不允许调用函数：" + name);
            }
            if (unqualified && SqlPolicy.ALLOWED_TIME_KEYS.contains(name)) {
                functions.add(name);
            }
        } else if (value instanceof Select query) {
            select(query, depth + 1);
        } else if (value instanceof Function function) {
            function(function, depth + 1);
        } else if (value instanceof TimeKeyExpression time) {
            String name = time.getStringValue().toUpperCase(Locale.ROOT);
            if (!SqlPolicy.ALLOWED_TIME_KEYS.contains(name)) {
                throw failure("SQL_FUNCTION_NOT_ALLOWED", "不允许调用函数：" + name);
            }
            functions.add(name);
        } else if (value instanceof AllColumns all) {
            if (nonEmpty(all.getExceptColumns()) || nonEmpty(all.getReplaceExpressions())) {
                unsupported(all);
            }
        } else if (value instanceof LikeExpression like) {
            if (like.getLikeKeyWord() != LikeExpression.KeyWord.LIKE) {
                unsupported(like);
            }
            expression(like.getLeftExpression(), depth + 1);
            expression(like.getRightExpression(), depth + 1);
            expression(like.getEscape(), depth + 1);
        } else if (value instanceof BinaryExpression binary && BINARY_OPERATORS.contains(value.getClass())) {
            expression(binary.getLeftExpression(), depth + 1);
            expression(binary.getRightExpression(), depth + 1);
        } else if (value instanceof ExpressionList<?> list
                && (value.getClass() == ExpressionList.class || value.getClass() == ParenthesedExpressionList.class)) {
            for (Expression item : list) {
                expression(item, depth + 1);
            }
        } else if (value instanceof SignedExpression signed) {
            expression(signed.getExpression(), depth + 1);
        } else if (value instanceof NotExpression not) {
            expression(not.getExpression(), depth + 1);
        } else if (value instanceof IsNullExpression isNull) {
            expression(isNull.getLeftExpression(), depth + 1);
        } else if (value instanceof IsBooleanExpression isBoolean) {
            expression(isBoolean.getLeftExpression(), depth + 1);
        } else if (value instanceof Between between) {
            expression(between.getLeftExpression(), depth + 1);
            expression(between.getBetweenExpressionStart(), depth + 1);
            expression(between.getBetweenExpressionEnd(), depth + 1);
        } else if (value instanceof InExpression in) {
            if (in.isGlobal() || in.getOldOracleJoinSyntax() != 0 || in.getOraclePriorPosition() != 0) {
                unsupported(in);
            }
            expression(in.getLeftExpression(), depth + 1);
            expression(in.getRightExpression(), depth + 1);
        } else if (value instanceof ExistsExpression exists) {
            expression(exists.getRightExpression(), depth + 1);
        } else if (value instanceof CaseExpression cases) {
            expression(cases.getSwitchExpression(), depth + 1);
            if (cases.getWhenClauses() != null) {
                for (WhenClause when : cases.getWhenClauses()) {
                    expression(when, depth + 1);
                }
            }
            expression(cases.getElseExpression(), depth + 1);
        } else if (value instanceof WhenClause when) {
            expression(when.getWhenExpression(), depth + 1);
            expression(when.getThenExpression(), depth + 1);
        } else if (value instanceof JdbcParameter || value instanceof JdbcNamedParameter) {
            throw failure("SQL_PARAMETER_NOT_ALLOWED", "模型 SQL 不允许包含未绑定参数。");
        } else {
            unsupported(value);
        }
    }

    private void function(Function function, int depth) {
        String name = function.getName() == null ? "" : function.getName().toUpperCase(Locale.ROOT);
        if (!SqlPolicy.ALLOWED_FUNCTIONS.contains(name) || function.getMultipartName().size() != 1) {
            throw failure("SQL_FUNCTION_NOT_ALLOWED", "不允许调用函数：" + name);
        }
        if (function.getNamedParameters() != null || function.getKeep() != null
                || nonEmpty(function.getOrderByElements()) || function.getAttributeColumn() != null
                || function.getLimit() != null || function.getHavingClause() != null
                || function.getExtraKeyword() != null || function.getOnOverflowTruncate() != null
                || function.isEscaped() || function.isUnique() || function.getNullHandling() != null
                || function.isIgnoreNulls() || function.isIgnoreNullsOutside()) {
            unsupported(function);
        }
        functions.add(name);
        expression(function.getParameters(), depth + 1);
    }

    private void tick(int depth) {
        if (depth > SqlPolicy.MAX_DEPTH || ++nodes > SqlPolicy.MAX_AST_NODES) {
            throw failure("SQL_TOO_COMPLEX", "SQL 语法结构过于复杂。");
        }
    }

    private static String identifier(String value) {
        if (value == null || value.isBlank()) {
            throw failure("SQL_MALFORMED", "表引用不完整。");
        }
        if (value.startsWith("`") && value.endsWith("`")) {
            return value.substring(1, value.length() - 1).replace("``", "`");
        }
        return value;
    }

    private static void boundedInteger(Expression value, long max, String clause) {
        if (!(value instanceof LongValue literal) || literal.getBigIntegerValue().signum() < 0
                || literal.getBigIntegerValue().compareTo(java.math.BigInteger.valueOf(max)) > 0) {
            throw failure("SQL_INVALID_LIMIT", clause + " 必须是 0 到 " + max + " 之间的整数。");
        }
    }

    private static boolean nonEmpty(java.util.Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    private static void unsupported(Object node) {
        String message = "当前不支持此查询语法，请使用规则中列出的普通查询形式。";
        if (node instanceof AnalyticExpression) {
            message = "当前暂不支持窗口函数。";
        } else if (node instanceof CastExpression) {
            message = "当前暂不支持 CAST 类型转换表达式。";
        } else if (node instanceof MySQLGroupConcat) {
            message = "当前暂不支持 GROUP_CONCAT 函数。";
        } else if (node instanceof Function) {
            message = "当前暂不支持函数的特殊参数或修饰。";
        }
        throw failure("SQL_UNSUPPORTED_SYNTAX", message);
    }

    private static SqlValidationException failure(String code, String message) {
        return new SqlValidationException(code, message);
    }
}
