package com.sqlfinder.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 只读授权脚本测试 */

class ReadonlyGrantGeneratorTest {
    private final ReadonlyGrantGenerator generator=new ReadonlyGrantGenerator();
    private ReadonlyGrantGenerator.Request request(String catalog,String username,String host,List<String> tables) {
        return new ReadonlyGrantGenerator.Request(catalog,username,host,tables);
    }
    @Test void generatesOnlySelectedTableGrantsAndPlaceholderPassword() {
        String sql=generator.generate(request("mybatis","sqlfinder_reader","localhost",List.of("tb_shop","tb_shop_type")));
        assertTrue(sql.contains("CREATE USER 'sqlfinder_reader'@'localhost'"));
        assertTrue(sql.contains("GRANT SELECT ON `mybatis`.`tb_shop`"));
        assertTrue(sql.contains("REPLACE_WITH_A_RANDOM_STRONG_PASSWORD"));
        assertFalse(sql.contains("IF NOT EXISTS")); assertFalse(sql.contains("WITH GRANT OPTION"));
        assertFalse(sql.contains("*.*")); assertFalse(sql.contains("GRANT ALL"));
    }
    @ParameterizedTest @ValueSource(strings={"'; DROP USER x; --","a`b","*","","x\n"})
    void invalidCatalogTableUserAndHostAreRejected(String value) {
        assertThrows(ModelSettingsException.class,()->generator.generate(request(value,"reader","localhost",List.of("t"))));
        assertThrows(ModelSettingsException.class,()->generator.generate(request("db",value,"localhost",List.of("t"))));
        assertThrows(ModelSettingsException.class,()->generator.generate(request("db","reader",value,List.of("t"))));
        assertThrows(ModelSettingsException.class,()->generator.generate(request("db","reader","localhost",List.of(value))));
    }
    @Test void emptyDuplicateAndExcessiveTablesRejected() {
        assertThrows(ModelSettingsException.class,()->generator.generate(request("db","reader","localhost",List.of())));
        assertThrows(ModelSettingsException.class,()->generator.generate(request("db","reader","localhost",List.of("t","t"))));
        assertThrows(ModelSettingsException.class,()->generator.generate(request("db","reader","localhost",java.util.stream.IntStream.range(0,51).mapToObj(i->"t"+i).toList())));
    }
}
