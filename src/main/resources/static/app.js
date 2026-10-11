/* 问数页面交互：配置读取、请求状态、SQL 摘要、结果表格及安全导出。 */
"use strict";

(() => {
    const el = (id) => document.getElementById(id);
    const state = { busy: false, policy: null, answer: null, page: 0, user: null, initializing: false, noTables: false };
    const { RequestError, requestJson, post, loginUrl } = window.SqlfinderAuth;
    const PAGE_SIZE = 50;
    const statuses = {
        NEEDS_CLARIFICATION: ["warning", "还需要一点信息"],
        SQL_VALIDATED: ["info", "SQL 已校验，尚未执行"],
        SQL_REJECTED: ["error", "SQL 未通过规则检查"],
        QUERY_SUCCEEDED: ["success", "查询完成"],
        QUERY_FAILED: ["error", "查询未完成"]
    };

    function requireLogin(reason = "expired") {
        clearAnswer();
        state.user = null;
        state.policy = null;
        el("source-tables").replaceChildren();
        el("source-summary").textContent = "请登录后查看授权数据。";
        el("current-user").textContent = "";
        el("user-controls").hidden = true;
        setBusy(false);
        location.replace(loginUrl(reason));
    }

    function node(tag, text, className = "") {
        const element = document.createElement(tag);
        element.textContent = text;
        if (className) element.className = className;
        return element;
    }

    function showStatus(tone, title, message, code = "", requestId = "", seconds = "") {
        el("status-banner").hidden = false;
        el("status-banner").className = `status-banner ${tone}`;
        el("status-title").textContent = title;
        el("message").textContent = message;
        el("error-detail").textContent = [code && `错误码：${code}`, requestId && `请求标识：${requestId}`].filter(Boolean).join(" · ");
        el("error-detail").hidden = !code && !requestId;
        el("elapsed").textContent = seconds ? `用时 ${seconds} s` : "";
    }

    function updateSubmitLabel() {
        el("submit-label").textContent = state.busy ? "正在处理…"
            : state.policy ? (state.policy.executionEnabled ? "查询数据" : "生成 SQL") : "提交问题";
    }

    function setBusy(busy) {
        state.busy = busy;
        for (const id of ["submit-button", "reset-button", "question"]) el(id).disabled = busy || !state.user;
        el("submit-button").disabled = busy || !state.user || state.noTables;
        el("logout-button").disabled = busy || !state.user;
        el("refresh-config").disabled = busy || state.initializing;
        document.querySelectorAll(".example-button").forEach((button) => { button.disabled = busy || !state.user || state.noTables; });
        document.querySelector(".answer-panel").setAttribute("aria-busy", String(busy));
        updateSubmitLabel();
    }

    function showNoTableAccess(message = "当前账号没有可用的数据表授权，请联系管理员配置。", code = "TABLE_ACCESS_DENIED", requestId = "") {
        state.noTables = true;
        clearAnswer();
        el("source-tables").replaceChildren();
        el("source-summary").textContent = "当前账号没有可查询的授权表。";
        showStatus("warning", "需要数据表授权", message, code, requestId);
    }

    function updateInput() {
        el("character-count").textContent = `${Array.from(el("question").value).length} 字`;
        el("question").removeAttribute("aria-invalid");
    }

    function clearAnswer() {
        state.answer = null;
        state.page = 0;
        for (const id of ["sql-section", "table-section", "response-details", "status-banner"]) el(id).hidden = true;
        el("response-details").open = false;
        el("empty-state").hidden = false;
        el("result-filter").value = "";
        el("result").textContent = "";
        el("sql").textContent = "";
        el("copy-sql").textContent = "复制 SQL";
        el("result-table").querySelector("thead").replaceChildren();
        el("result-table").querySelector("tbody").replaceChildren();
    }

    function renderChips(id, values, className) {
        el(id).replaceChildren(...(values.length ? values : ["无"]).map((value) => node("span", value, className)));
    }

    function validateAnswer(data) {
        if (!data || !Object.hasOwn(statuses, data.status)
            || (data.sql != null && typeof data.sql !== "string")) {
            throw new RequestError("protocol", "服务返回的查询状态不正确，请稍后重试。");
        }
        if (data.status === "QUERY_SUCCEEDED" && (!Array.isArray(data.columns)
            || !data.columns.length || !data.columns.every((c) => typeof c === "string")
            || !Array.isArray(data.rows) || !data.rows.every((r) => r && typeof r === "object" && !Array.isArray(r)))) {
            throw new RequestError("protocol", "服务返回的数据格式不正确，请稍后重试。");
        }
    }

    function renderAnswer(data, seconds) {
        validateAnswer(data);
        state.answer = data;
        el("empty-state").hidden = true;
        const [tone, title] = statuses[data.status];
        showStatus(tone, title, data.message || "处理完成。", data.errorCode || "", "", seconds);
        el("response-details").hidden = false;
        el("result").textContent = JSON.stringify(data, null, 2);
        if (data.sql) {
            el("sql-section").hidden = false;
            el("sql").textContent = data.sql;
            el("line-numbers").textContent = data.sql.split("\n").map((_, i) => i + 1).join("\n");
            const validation = data.validation;
            el("validation").hidden = !validation;
            renderChips("validation-tables", Array.isArray(validation?.tables) ? validation.tables : [], "chip");
            renderChips("validation-functions", Array.isArray(validation?.functions) ? validation.functions : [], "chip function");
        }
        if (data.status === "QUERY_SUCCEEDED") {
            el("table-section").hidden = false;
            el("row-count").textContent = `${data.rows.length} 行`;
            el("truncation-note").hidden = !data.truncated;
            el("export-csv").disabled = data.rows.length === 0;
            const header = document.createElement("tr");
            const index = node("th", "#", "row-index");
            index.scope = "col";
            header.append(index);
            for (const column of data.columns) {
                const th = node("th", column);
                th.scope = "col";
                th.title = column;
                header.append(th);
            }
            el("result-table").querySelector("thead").replaceChildren(header);
            renderTable();
        }
    }

    function cellValue(row, column) { return Object.hasOwn(row, column) ? row[column] : null; }
    function displayValue(value) { return value == null ? "NULL" : String(value); }

    function filteredRows() {
        const query = el("result-filter").value.trim().toLocaleLowerCase();
        const { rows, columns } = state.answer;
        return rows.map((row, index) => ({ row, index })).filter(({ row }) => !query
            || columns.some((column) => displayValue(cellValue(row, column)).toLocaleLowerCase().includes(query)));
    }

    function renderTable() {
        if (state.answer?.status !== "QUERY_SUCCEEDED") return;
        const matches = filteredRows();
        const pageCount = Math.max(1, Math.ceil(matches.length / PAGE_SIZE));
        state.page = Math.min(state.page, pageCount - 1);
        const start = state.page * PAGE_SIZE;
        const fragment = document.createDocumentFragment();
        for (const { row, index } of matches.slice(start, start + PAGE_SIZE)) {
            const tr = document.createElement("tr");
            tr.append(node("td", index + 1, "row-index"));
            for (const column of state.answer.columns) {
                const value = cellValue(row, column);
                const td = node("td", displayValue(value), value == null ? "null-value" : typeof value === "number" ? "numeric" : "");
                td.title = displayValue(value);
                tr.append(td);
            }
            fragment.append(tr);
        }
        el("result-table").querySelector("tbody").replaceChildren(fragment);
        el("filter-count").textContent = el("result-filter").value.trim() ? `匹配 ${matches.length} / ${state.answer.rows.length} 行` : "";
        el("table-empty").hidden = matches.length !== 0;
        el("table-empty").textContent = state.answer.rows.length ? "没有匹配的已返回数据，试试其他关键词。" : "查询成功，没有符合条件的数据。";
        el("pagination").hidden = matches.length <= PAGE_SIZE;
        el("page-info").textContent = `${start + 1}–${Math.min(start + PAGE_SIZE, matches.length)} / ${matches.length} 行`;
        el("previous-page").disabled = state.page === 0;
        el("next-page").disabled = state.page >= pageCount - 1;
        el("table-scroll").scrollTop = 0;
    }

    async function loadConfig() {
        el("refresh-config").disabled = true;
        const results = await Promise.allSettled([
            requestJson("/api/chat/sql-policy", {}, 10000), requestJson("/api/datasource/schema", {}, 10000)
        ]);
        if (results.some((result) => result.status === "rejected" && result.reason.status === 401)) {
            requireLogin();
            return;
        }
        const policyResult = results[0];
        if (policyResult.status === "fulfilled" && typeof policyResult.value?.executionEnabled === "boolean") {
            state.policy = policyResult.value;
            const enabled = state.policy.executionEnabled;
            el("connection-label").textContent = "服务已连接";
            el("connection-dot").className = "connection-dot connected";
            el("mode-badge").textContent = enabled ? "查询模式 · 已开启执行" : "校验模式 · 仅生成 SQL";
            el("mode-badge").className = `mode-badge ${enabled ? "execution-mode" : "validation-mode"}`;
            el("query-limits").textContent = `查询超时 ${state.policy.queryTimeoutSeconds} 秒 · 最多返回 ${state.policy.maxResultRows} 行`;
            el("mode-note").textContent = enabled ? "提交问题后调用模型，校验通过后执行只读查询。"
                : "当前仅生成并校验 SQL，不执行查询。提交问题会调用模型。";
        } else {
            state.policy = null;
            el("connection-label").textContent = "配置读取失败";
            el("connection-dot").className = "connection-dot";
            el("mode-badge").textContent = "执行模式未知";
            el("mode-badge").className = "mode-badge";
            el("query-limits").textContent = "查询限制暂不可用，可刷新配置重试。";
            el("mode-note").textContent = "暂时无法确认执行模式。提交后以服务端返回的状态为准。";
        }
        updateSubmitLabel();
        el("source-tables").replaceChildren();
        const schemaResult = results[1];
        if (schemaResult.status === "fulfilled" && Array.isArray(schemaResult.value)) {
            const tables = schemaResult.value.filter((table) => table && typeof table.tableName === "string");
            const wasDenied = state.noTables;
            state.noTables = tables.length === 0;
            if (state.noTables) showNoTableAccess();
            else if (wasDenied) clearAnswer();
            const catalogs = [...new Set(tables.map((table) => table.catalog).filter(Boolean))];
            el("source-summary").textContent = tables.length ? `${catalogs.join("、")} · ${tables.length} 张授权表` : "当前没有可查询的授权表。";
            for (const table of tables) {
                const details = document.createElement("details");
                details.className = "source-table";
                details.append(node("summary", table.tableName));
                const list = document.createElement("ul");
                if (table.comment) list.append(node("li", table.comment));
                for (const column of Array.isArray(table.columns) ? table.columns : []) {
                    list.append(node("li", `${column.name} · ${column.dataType}${column.comment ? ` · ${column.comment}` : ""}`));
                }
                details.append(list);
                el("source-tables").append(details);
            }
        } else if (schemaResult.status === "rejected" && schemaResult.reason.code === "TABLE_ACCESS_DENIED") {
            showNoTableAccess(schemaResult.reason.message, schemaResult.reason.code, schemaResult.reason.requestId);
        } else {
            el("source-summary").textContent = "暂时无法读取授权表，可刷新配置重试。";
        }
        setBusy(state.busy);
        el("refresh-config").disabled = false;
    }

    el("question-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        if (state.busy || !state.user || state.noTables) return;
        const question = el("question").value.trim();
        clearAnswer();
        if (!question) {
            el("question").setAttribute("aria-invalid", "true");
            showStatus("warning", "先写下你的问题", "输入想查询的内容，或选择左侧的示例。");
            el("question").focus();
            return;
        }
        setBusy(true);
        el("empty-state").hidden = true;
        showStatus("pending", "正在处理你的问题", "正在生成并校验 SQL，请稍候。开启查询时还会读取数据。");
        const started = performance.now();
        try {
            const data = await post("/api/chat/question", {
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ question })
            });
            renderAnswer(data, ((performance.now() - started) / 1000).toFixed(1));
        } catch (error) {
            if (error.status === 401) { requireLogin(); return; }
            if (error.code === "TABLE_ACCESS_DENIED") {
                showNoTableAccess(error.message, error.code, error.requestId);
                return;
            }
            const known = error instanceof RequestError;
            showStatus("error", known && error.kind === "network" ? "暂时无法获取答案" : "请求未完成",
                known ? error.message : "页面无法显示这次响应，请刷新后重试。", known ? error.code : "", known ? error.requestId : "");
        } finally {
            setBusy(false);
            if (window.matchMedia("(max-width: 800px)").matches) {
                el("status-banner").scrollIntoView({ block: "start",
                    behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
            }
        }
    });

    el("question").addEventListener("input", updateInput);
    el("question").addEventListener("keydown", (event) => {
        if (event.key === "Enter" && !event.isComposing && (event.ctrlKey || event.metaKey)) {
            event.preventDefault();
            if (!state.busy) el("question-form").requestSubmit();
        }
    });
    document.querySelectorAll(".example-button").forEach((button) => button.addEventListener("click", () => {
        el("question").value = button.dataset.question;
        updateInput();
        el("question").focus();
    }));
    el("reset-button").addEventListener("click", () => {
        clearAnswer();
        el("question").value = "";
        updateInput();
        el("question").focus();
    });
    el("refresh-config").addEventListener("click", initialize);
    el("result-filter").addEventListener("input", () => { state.page = 0; renderTable(); });
    el("previous-page").addEventListener("click", () => { state.page = Math.max(0, state.page - 1); renderTable(); });
    el("next-page").addEventListener("click", () => { state.page++; renderTable(); });

    el("copy-sql").addEventListener("click", async () => {
        const answer = state.answer;
        if (!answer?.sql) return;
        try {
            await navigator.clipboard.writeText(answer.sql);
            if (state.answer === answer) el("copy-sql").textContent = "已复制";
        } catch {
            if (state.answer === answer) el("copy-sql").textContent = "复制失败，请选中 SQL 复制";
        }
    });

    function csvCell(value) {
        let text = value == null ? "" : String(value);
        // 字符串或列名以公式标记开头时加单引号；数值负号不需要处理。
        if (typeof value === "string" && /^[\s\uFEFF]*[=+@-]/.test(text)) text = "'" + text;
        return `"${text.replaceAll('"', '""')}"`;
    }

    el("export-csv").addEventListener("click", () => {
        const answer = state.answer;
        if (answer?.status !== "QUERY_SUCCEEDED" || !answer.rows.length) return;
        // 导出全部已返回的行，不受页面筛选或分页影响；不会再次查询数据库。
        const lines = [answer.columns.map(csvCell).join(","), ...answer.rows.map((row) =>
            answer.columns.map((column) => csvCell(cellValue(row, column))).join(","))];
        const blob = new Blob(["\uFEFF", lines.join("\r\n")], { type: "text/csv;charset=utf-8" });
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = `sqlfinder-${new Date().toISOString().slice(0, 10)}.csv`;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    });

    el("logout-button").addEventListener("click", async () => {
        if (state.busy) return;
        setBusy(true);
        try {
            await post("/api/auth/logout");
            requireLogin("loggedout");
        } catch (error) {
            if (error.status === 401) { requireLogin(); return; }
            showStatus("error", "退出未完成", error.message || "请稍后重试。", error.code, error.requestId);
        } finally { setBusy(false); }
    });

    async function initialize() {
        if (state.initializing) return;
        state.initializing = true;
        el("refresh-config").disabled = true;
        try {
            const user = await requestJson("/api/auth/me", {}, 10000);
            if (typeof user?.username !== "string" || !user.username) throw new RequestError("protocol", "无法确认当前用户，请刷新重试。");
            state.user = user;
            el("current-user").textContent = user.username;
            el("current-user").title = user.username;
            el("user-controls").hidden = false;
            await loadConfig();
        } catch (error) {
            if (error.status === 401) { requireLogin(state.user ? "expired" : ""); return; }
            clearAnswer();
            state.user = null;
            state.policy = null;
            el("user-controls").hidden = true;
            el("source-tables").replaceChildren();
            el("source-summary").textContent = "无法确认登录状态，请刷新重试。";
            showStatus("error", "无法确认登录状态", error.message || "请刷新页面后重试。");
            el("connection-label").textContent = "服务暂不可用";
        } finally {
            state.initializing = false;
            el("refresh-config").disabled = false;
            setBusy(state.busy);
        }
    }
    window.addEventListener("pageshow", (event) => {
        if (event.persisted) { clearAnswer(); state.user = null; setBusy(false); initialize(); }
    });
    setBusy(false);
    initialize();
})();
