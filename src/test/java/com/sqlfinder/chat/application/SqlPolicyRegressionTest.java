package com.sqlfinder.chat.application;

import com.sqlfinder.chat.model.ValidatedSql;
import com.sqlfinder.datasource.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SQL规则回归测试 */
class SqlPolicyRegressionTest {
    private final SqlValidator validator = new SqlValidator();
    private final List<TableSchema> schemas = List.of(
            new TableSchema("mybatis", "tb_shop", "", List.of()),
            new TableSchema("mybatis", "tb_shop_type", "", List.of())
    );

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT(*) FROM tb_shop",
            "SELECT COALESCE(ROUND(AVG(score), 2), 0) FROM tb_shop",
            "SELECT s.id, t.name FROM tb_shop s LEFT JOIN tb_shop_type t ON s.type_id = t.id",
            "SELECT COUNT(DISTINCT type_id) FROM tb_shop",
            "SELECT CASE WHEN score BETWEEN 1 AND 5 THEN 'low' ELSE 'high' END FROM tb_shop",
            "SELECT id FROM tb_shop WHERE id IN (1, 2, 3) AND name LIKE '%店%'",
            "SELECT id FROM tb_shop WHERE EXISTS (SELECT id FROM tb_shop_type WHERE id = tb_shop.type_id)",
            "SELECT COUNT(*) FROM tb_shop HAVING COUNT(*) > 0",
            "SELECT id FROM tb_shop UNION ALL SELECT id FROM tb_shop_type ORDER BY id LIMIT 10",
            "(SELECT id FROM tb_shop) ORDER BY id LIMIT 10",
            "SELECT '-- SLEEP(1); /* INTO OUTFILE */' AS text FROM tb_shop",
            "SELECT 'O''Reilly' FROM tb_shop",
            "SELECT DATE_FORMAT(create_time, '%Y-%m'), YEAR(create_time) FROM tb_shop",
            "SELECT id FROM tb_shop LIMIT 5, 10",
            "SELECT id FROM tb_shop LIMIT 10 OFFSET 5",
            "SELECT NOW(), CURRENT_DATE, CURRENT_TIMESTAMP FROM tb_shop",
            "SELECT * FROM (SELECT id FROM tb_shop) tb_user",
            "SELECT IF(score IS NULL, 0, score), -score FROM tb_shop",
            "SELECT id FROM `mybatis`.`tb_shop`;"
    })
    void acceptsSupportedBusinessQueries(String sql) {
        assertDoesNotThrow(() -> validator.validate(sql, schemas), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT SLEEP(1) FROM tb_shop",
            "SELECT BENCHMARK(1000, 1 + 1) FROM tb_shop",
            "SELECT LOAD_FILE('/tmp/example.txt') FROM tb_shop",
            "SELECT VERSION(), CURRENT_USER(), USER(), DATABASE() FROM tb_shop",
            "SELECT CURRENT_USER FROM tb_shop",
            "SELECT SESSION_USER FROM tb_shop",
            "SELECT SYSTEM_USER FROM tb_shop",
            "SELECT CURRENT_ROLE FROM tb_shop",
            "SELECT mybatis.COUNT(*) FROM tb_shop",
            "SELECT COALESCE(SLEEP(1), 0) FROM tb_shop",
            "SELECT CASE WHEN 1 = 1 THEN SLEEP(1) ELSE 0 END FROM tb_shop",
            "SELECT COUNT(*) FROM tb_shop HAVING SLEEP(1) = 0",
            "SELECT id FROM tb_shop JOIN tb_shop_type t ON SLEEP(1) = 0",
            "SELECT id FROM tb_shop GROUP BY SLEEP(1)",
            "SELECT id FROM tb_shop ORDER BY SLEEP(1)",
            "SELECT id FROM tb_shop UNION SELECT id FROM tb_shop_type ORDER BY SLEEP(1)",
            "(SELECT id FROM tb_shop) ORDER BY SLEEP(1)",
            "SELECT ROW_NUMBER() OVER (ORDER BY id) FROM tb_shop",
            "SELECT COUNT(*) OVER (ORDER BY (SELECT id FROM tb_user)) FROM tb_shop",
            "SELECT GROUP_CONCAT(name) FROM tb_shop",
            "SELECT CAST((SELECT id FROM tb_user LIMIT 1) AS CHAR) FROM tb_shop",
            "SELECT @secret FROM tb_shop",
            "SELECT @@version FROM tb_shop",
            "SELECT @x := id FROM tb_shop",
            "SELECT id FROM tb_shop WHERE id = ?",
            "SELECT id FROM tb_shop WHERE id = :id",
            "SELECT * FROM tb_shop INTO OUTFILE '/tmp/export.csv'",
            "SELECT * FROM tb_shop INTO DUMPFILE '/tmp/export.bin'",
            "SELECT id INTO tb_shop_type FROM tb_shop",
            "(SELECT * FROM tb_shop) FOR UPDATE",
            "SELECT id FROM tb_shop UNION SELECT id FROM tb_shop_type FOR UPDATE",
            "SELECT * FROM tb_shop LOCK IN SHARE MODE",
            "SELECT * FROM (SELECT * FROM tb_shop FOR SHARE) s",
            "WITH s AS (SELECT * FROM tb_shop) SELECT * FROM s",
            "SELECT id FROM tb_shop INTERSECT SELECT id FROM tb_shop_type",
            "TABLE tb_shop",
            "SHOW TABLES",
            "EXPLAIN SELECT * FROM tb_shop",
            "CALL some_procedure()",
            "SELECT id FROM tb_shop; SELECT id FROM tb_shop_type",
            "SELECT id FROM tb_shop; DELETE FROM tb_shop",
            "SELECT id FROM tb_shop /* comment */",
            "SELECT id FROM tb_shop -- comment",
            "SELECT id FROM tb_shop # comment",
            "SELECT /*! SLEEP(1), */ id FROM tb_shop",
            "SELECT /*+ hint */ id FROM tb_shop",
            "SELECT * FROM tb_shop LIMIT ALL",
            "SELECT * FROM tb_shop LIMIT 1001",
            "SELECT * FROM tb_shop LIMIT 999999999999999999999999999999",
            "SELECT * FROM tb_shop LIMIT ?",
            "SELECT * FROM tb_shop LIMIT 10 OFFSET 100001",
            "SELECT 'back\\slash' FROM tb_shop",
            "SELECT \"ambiguous literal\" FROM tb_shop",
            "SELECT 1"
    })
    void rejectsDangerousOrUnsupportedQueries(String sql) {
        assertThrows(SqlValidationException.class, () -> validator.validate(sql, schemas), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM other_db.tb_shop",
            "SELECT * FROM `other_db`.`tb_shop`",
            "SELECT * FROM information_schema.tables",
            "SELECT * FROM mysql.user",
            "SELECT * FROM TB_SHOP",
            "SELECT * FROM (SELECT * FROM tb_user) tb_user",
            "SELECT id FROM tb_shop WHERE id IN (SELECT id FROM tb_user)",
            "SELECT id FROM tb_shop ORDER BY (SELECT id FROM tb_user LIMIT 1)",
            "SELECT COUNT(*) FROM tb_shop GROUP BY (SELECT id FROM tb_user LIMIT 1)",
            "SELECT id FROM tb_shop UNION SELECT id FROM tb_shop_type ORDER BY (SELECT id FROM tb_user LIMIT 1)",
            "(SELECT id FROM tb_shop) ORDER BY (SELECT id FROM tb_user LIMIT 1)",
            "SELECT id FROM tb_shop JOIN tb_shop_type t ON t.id = (SELECT id FROM tb_user LIMIT 1)",
            "SELECT CASE WHEN 1 = 1 THEN (SELECT id FROM tb_user LIMIT 1) ELSE 0 END FROM tb_shop"
    })
    void doesNotLosePhysicalTablesOrCatalogs(String sql) {
        SqlValidationException error = assertThrows(SqlValidationException.class,
                () -> validator.validate(sql, schemas), sql);
        assertEquals("SQL_UNAUTHORIZED_TABLE", error.getCode(), sql);
    }

    @Test
    void returnsStableSortedSummaryWithoutRewritingSql() {
        String sql = " SELECT COUNT(*), COALESCE(ROUND(AVG(score), 2), 0) FROM `mybatis`.`tb_shop`; ";
        ValidatedSql result = validator.validate(sql, schemas);
        assertEquals(sql.strip(), result.sql());
        assertEquals(List.of("mybatis.tb_shop"), result.validation().tables());
        assertEquals(List.of("AVG", "COALESCE", "COUNT", "ROUND"), result.validation().functions());
        assertEquals(SqlPolicy.VERSION, result.validation().policyVersion());
        assertThrows(UnsupportedOperationException.class, () -> result.validation().functions().add("SLEEP"));
    }

    @Test
    void rejectsAmbiguousQuotedDottedIdentifiers() {
        List<TableSchema> dottedSchema = List.of(new TableSchema("mybatis", "other.tb_shop", "", List.of()));
        assertThrows(SqlValidationException.class, () -> validator.validate("SELECT * FROM other.tb_shop", dottedSchema));
        assertThrows(SqlValidationException.class, () -> validator.validate("SELECT * FROM `other.tb_shop`", dottedSchema));
        assertThrows(SqlValidationException.class, () -> validator.validate("SELECT * FROM `tb_shop@link`", schemas));
    }

    @Test
    void rejectsEmptyMalformedAndOverlyComplexInputs() {
        for (String sql : new String[]{null, "", " ", "SELECT (", "SELECT 'unclosed FROM tb_shop", "SELECT ) FROM tb_shop"}) {
            assertThrows(SqlValidationException.class, () -> validator.validate(sql, schemas));
        }
        assertThrows(SqlValidationException.class,
                () -> validator.validate("SELECT '" + "x".repeat(SqlPolicy.MAX_SQL_LENGTH) + "' FROM tb_shop", schemas));
        assertThrows(SqlValidationException.class,
                () -> validator.validate("SELECT " + "(".repeat(33) + "id" + ")".repeat(33) + " FROM tb_shop", schemas));
        assertThrows(SqlValidationException.class,
                () -> validator.validate("SELECT " + "1 + ".repeat(100) + "1 FROM tb_shop", schemas));
        assertThrows(SqlValidationException.class,
                () -> validator.validate("SELECT id FROM tb_shop WHERE id IN (" + "1,".repeat(1600) + "1)", schemas));
    }

    @Test
    void rejectsMissingOrAmbiguousAuthorizationContext() {
        assertThrows(SqlValidationException.class, () -> validator.validate("SELECT * FROM tb_shop", null));
        assertThrows(SqlValidationException.class, () -> validator.validate("SELECT * FROM tb_shop", List.of()));
        assertThrows(SqlValidationException.class, () -> validator.validate("SELECT * FROM tb_shop",
                List.of(new TableSchema(null, "tb_shop", "", List.of()))));
        assertThrows(SqlValidationException.class, () -> validator.validate("SELECT * FROM tb_shop",
                List.of(schemas.get(0), new TableSchema("other", "tb_shop", "", List.of()))));
    }
}
