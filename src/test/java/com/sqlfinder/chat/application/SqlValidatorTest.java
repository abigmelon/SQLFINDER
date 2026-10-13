package com.sqlfinder.chat.application;

import com.sqlfinder.datasource.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SQL基础校验测试 */
class SqlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT id INTO tb_shop_type FROM tb_shop",
            "SELECT * FROM tb_shop FOR UPDATE",
            "SELECT * FROM tb_shop FOR SHARE",
            "SELECT * FROM (SELECT * FROM tb_shop FOR UPDATE) s",
            "SELECT id FROM tb_shop ORDER BY (SELECT id FROM tb_user LIMIT 1)",
            "SELECT COUNT(*) FROM tb_shop GROUP BY (SELECT id FROM tb_user LIMIT 1)"
    })
    void rejectsUnsafeSelectStructure(String sql) {
        assertThrows(IllegalArgumentException.class,
                () -> validator.checkTableAccess(sql, authorizedSchemas));
    }

    @Test
    void acceptsOrdinaryGroupingAndOrdering() {
        assertDoesNotThrow(() -> validator.checkTableAccess(
                """
                        SELECT type_id, COUNT(*)
                        FROM tb_shop
                        GROUP BY type_id
                        ORDER BY type_id
                        """,
                authorizedSchemas
        ));
    }

    private final SqlValidator validator = new SqlValidator();

    private final List<TableSchema> authorizedSchemas = List.of(
            new TableSchema("mybatis", "tb_shop", "", List.of()),
            new TableSchema("mybatis", "tb_shop_type", "", List.of())
    );

    @Test
    void acceptsSingleSelect() {
        assertDoesNotThrow(() -> validator.parseSingleSelect(
                "SELECT COUNT(*) AS shop_count FROM tb_shop;"
        ));
    }

    @Test
    void rejectsMultipleStatements() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.parseSingleSelect(
                        "SELECT * FROM tb_shop; DELETE FROM tb_shop;"
                ));
    }

    @Test
    void rejectsDelete() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.parseSingleSelect(
                        "DELETE FROM tb_shop"
                ));
    }

    @Test
    void rejectsInvalidSql() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.parseSingleSelect("SELECT ("));
    }

    @Test
    void acceptsAuthorizedTable() {
        assertDoesNotThrow(() -> validator.checkTableAccess(
                "SELECT COUNT(*) FROM `mybatis`.`tb_shop`",
                authorizedSchemas
        ));
    }

    @Test
    void rejectsUnauthorizedTable() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.checkTableAccess(
                        "SELECT * FROM tb_user",
                        authorizedSchemas
                ));
    }

    @Test
    void rejectsOtherDatabase() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.checkTableAccess(
                        "SELECT * FROM other_db.tb_shop",
                        authorizedSchemas
                ));
    }

    @Test
    void rejectsUnauthorizedTableInSubquery() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.checkTableAccess(
                        """
                                SELECT * FROM tb_shop
                                WHERE id IN (SELECT id FROM tb_user)
                                """,
                        authorizedSchemas
                ));
    }
}
