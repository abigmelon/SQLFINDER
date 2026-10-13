package com.sqlfinder.chat.model;

import java.util.List;

/** SQL校验摘要 */
public record SqlValidationSummary(String policyVersion, List<String> tables, List<String> functions) {
    public SqlValidationSummary {
        tables = List.copyOf(tables);
        functions = List.copyOf(functions);
    }
}
