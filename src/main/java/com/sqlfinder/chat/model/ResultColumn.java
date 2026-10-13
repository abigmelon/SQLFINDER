package com.sqlfinder.chat.model;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/** 查询字段信息 */
public record ResultColumn(String name, String dataType, String kind, Boolean nullable,
                           int precision, int scale, String encoding) {
    public static ResultColumn from(ResultSetMetaData metadata, int index, String name) throws SQLException {
        int type=metadata.getColumnType(index);
        String kind=switch(type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> "INTEGER";
            case Types.NUMERIC, Types.DECIMAL -> "DECIMAL";
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> "NUMBER";
            case Types.DATE -> "DATE";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "DATETIME";
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> "TIME";
            case Types.BOOLEAN -> "BOOLEAN";
            case Types.BIT -> metadata.getPrecision(index)>1 ? "BINARY" : "BOOLEAN";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> "TEXT";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "BINARY";
            default -> "OTHER";
        };
        String dataType=metadata.getColumnTypeName(index);
        if (dataType==null || dataType.isBlank()) dataType="UNKNOWN";
        if (dataType.length()>128) dataType=dataType.substring(0,128);
        int nullable=metadata.isNullable(index);
        return new ResultColumn(name,dataType,kind,nullable==ResultSetMetaData.columnNullableUnknown ? null : nullable==ResultSetMetaData.columnNullable,
                Math.max(0,metadata.getPrecision(index)),metadata.getScale(index),
                switch(kind) { case "INTEGER" -> "number-or-exact-string"; case "DECIMAL" -> "exact-string";
                    case "NUMBER" -> "number"; case "BOOLEAN" -> "boolean"; case "BINARY" -> "base64"; default -> "string"; });
    }
}
