package com.sqlfinder.datasource.application;

import com.sqlfinder.datasource.model.ColumnSchema;
import com.sqlfinder.datasource.model.TableSchema;

import java.util.List;

/** 授权表结构接口 */
public interface MetadataService {

   // 全局白名单结构；面向用户的调用必须通过 UserMetadataService 收窄范围。
   List<TableSchema> getAuthorizedSchemas();
}
