package com.sqlfinder.chat.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.ResultSetMetaData;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 查询字段分类测试 */
class ResultColumnTest {
    @org.junit.jupiter.api.Test void multiBitValuesAreBinaryRatherThanBooleanOrNumericMetrics() throws Exception {
        var metadata=mock(ResultSetMetaData.class);when(metadata.getColumnType(1)).thenReturn(java.sql.Types.BIT);when(metadata.getPrecision(1)).thenReturn(64);
        var column=ResultColumn.from(metadata,1,"flags");assertEquals("BINARY",column.kind());assertEquals("base64",column.encoding());
    }
    @ParameterizedTest @CsvSource({"-5,INTEGER,number-or-exact-string","4,INTEGER,number-or-exact-string",
            "3,DECIMAL,exact-string","2,DECIMAL,exact-string","8,NUMBER,number","6,NUMBER,number",
            "91,DATE,string","93,DATETIME,string","2014,DATETIME,string","92,TIME,string",
            "16,BOOLEAN,boolean","-7,BOOLEAN,boolean","12,TEXT,string","-3,BINARY,base64","2004,OTHER,string"})
    void jdbcTypesKeepTheirMeaning(int type,String kind,String encoding) throws Exception {
        var metadata=mock(ResultSetMetaData.class); when(metadata.getColumnType(1)).thenReturn(type);
        when(metadata.isNullable(1)).thenReturn(ResultSetMetaData.columnNullable);
        var column=ResultColumn.from(metadata,1,"result");
        assertEquals(kind,column.kind()); assertEquals(encoding,column.encoding()); assertTrue(column.nullable());
        assertEquals("UNKNOWN",column.dataType());
    }
}
