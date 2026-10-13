/* 设置页面交互 */
"use strict";
(() => {
    const { requestJson, post, loginUrl } = window.SqlfinderAuth;
    const el = (id) => document.getElementById(id);
    let busy = false, loaded = false, databaseBusy = false, databaseView = null, candidateChecked = false, generation = 0;
    function feedback(message, tone = "") {
        el("feedback").textContent = message;
        el("feedback").dataset.tone = tone;
    }
    function setBusy(value) {
        busy = value;
        el("model-form").setAttribute("aria-busy", String(value));
        for (const control of el("model-form").elements) control.disabled = value;
        el("api-key").disabled = value || el("clear-key").checked;
    }
    function display(view) {
        if (typeof view?.model !== "string" || typeof view.apiKeyConfigured !== "boolean"
            || !Number.isInteger(view.timeoutSeconds) || view.baseUrl !== "https://api.deepseek.com") {
            throw new Error("配置响应格式不正确，请刷新页面后重试。");
        }
        el("model-name").value = view.model;
        el("timeout").value = view.timeoutSeconds;
        el("api-key").value = "";
        el("clear-key").checked = false;
        el("key-state").textContent = view.apiKeyConfigured ? "密钥已配置" : "未配置密钥";
        el("save-note").textContent = view.source === "saved"
            ? "已使用网页保存的配置。下一次问数生效，重启后继续保留。"
            : "当前使用启动配置。保存后下一次问数生效，重启后继续保留。";
    }
    function failure(error) {
        if (error.status === 401) { window.location.replace(loginUrl("expired")); return; }
        if (error.status === 403 && error.code !== "CSRF_INVALID") {
            loaded = false;
            el("api-key").value = "";
            el("db-password").value = "";
            setBusy(false); setDatabaseBusy(false);
            generation++;
            el("model-form").hidden = true;
            el("database-controls").hidden = true;
            databaseView = null;
        }
        feedback(error.message || "操作未完成，请稍后重试。", "error");
    }
    async function load() {
        generation++;
        const version = generation;
        setBusy(false); setDatabaseBusy(false);
        loaded = false;
        databaseView = null;
        el("database-controls").hidden = true;
        el("database-results").hidden = true;
        el("grant-output").hidden = true;
        el("grant-sql").textContent = "";
        el("api-key").value = "";
        el("db-password").value = "";
        invalidateCandidate();
        el("model-form").hidden = true;
        try {
            const access = await requestJson("/api/settings/access");
            if (version !== generation) return;
            if (access?.canManageSettings !== true) {
                feedback("当前账号没有修改设置的权限。请使用管理员账号登录。", "error");
                databaseFeedback("当前账号没有管理数据库设置的权限。", "error");
                return;
            }
            const model = await requestJson("/api/settings/model");
            if (version !== generation) return;
            display(model);
            loaded = true;
            el("model-form").hidden = false;
            feedback("配置已读取。连接测试不会生成回答，也不会保存配置。");
            void loadDatabase();
        } catch (error) { if (version === generation) failure(error); }
    }
    function databaseFeedback(message, tone = "") {
        el("database-feedback").textContent = message;
        el("database-feedback").dataset.tone = tone;
    }
    function databaseFailure(error) {
        if (error.status === 401 || (error.status === 403 && error.code !== "CSRF_INVALID")) failure(error);
        databaseFeedback(error.message || "数据库操作未完成，请稍后重试。", "error");
    }
    async function loadDatabase() {
        const version = generation;
        try {
            const view = await requestJson("/api/settings/database");
            if (version !== generation || !loaded) return;
            if (typeof view?.catalog !== "string" || !Array.isArray(view.tables)
                || !view.tables.every(t => typeof t === "string")) throw new Error("数据库配置响应格式不正确。");
            databaseView = view;
            el("database-catalog").textContent = view.catalog;
            renderGrantTables(view.tables);
            displayConnection(view.connection);
            el("database-controls").hidden = false;
            databaseFeedback("配置已读取，点击检查可验证当前账号与表权限。");
        } catch (error) { if (version === generation) databaseFailure(error); }
    }
    function setDatabaseBusy(value) {
        databaseBusy = value;
        el("database-controls").setAttribute("aria-busy", String(value));
        for (const control of el("grant-form").elements) control.disabled = value;
        for (const control of el("connection-form").elements) control.disabled = value;
        el("db-password").disabled = value || el("db-clear-password").checked;
        el("check-database").disabled = value;
        el("copy-grants").disabled = value;
    }
    function renderGrantTables(tables) {
        el("grant-tables").replaceChildren(...tables.map(table => checkbox(table,true)));
    }
    function checkbox(table, checked, suffix = "") {
        const label = document.createElement("label"); label.className = "check";
        const input = document.createElement("input"); input.type = "checkbox"; input.value = table; input.checked = checked;
        label.append(input,document.createTextNode(table + suffix)); return label;
    }
    function displayConnection(view) {
        if (!view || typeof view.pendingRestart !== "boolean") throw new Error("数据库连接配置响应格式不正确。");
        const c = view.saved || view.active;
        if (c && (typeof c.host !== "string" || !Number.isInteger(c.port) || typeof c.catalog !== "string"
            || typeof c.username !== "string" || typeof c.passwordConfigured !== "boolean" || typeof c.requireTls !== "boolean"
            || !Array.isArray(c.tables) || !c.tables.every(t => typeof t === "string"))) throw new Error("数据库连接配置响应格式不正确。");
        el("db-host").value = c?.host || "127.0.0.1";
        el("db-port").value = c?.port || 3306;
        el("db-catalog").value = c?.catalog || databaseView.catalog;
        el("db-username").value = c?.username || "sqlfinder_reader";
        el("db-password").value = ""; el("db-clear-password").checked = false; el("db-tls").checked = c?.requireTls || false;
        el("db-password-state").textContent = c?.passwordConfigured ? "密码已配置" : "未配置密码";
        el("connection-state").textContent = view.pendingRestart ? "待重启生效" : "当前连接";
        el("connection-note").textContent = view.pendingRestart
            ? `已保存 ${c.host}:${c.port}/${c.catalog}，尚未用于问数。重启服务后，连接与全局表范围一起启用；登录账号的表授权不会自动扩大。`
            : view.active ? "表单来自当前配置。测试候选连接不会改变正在使用的数据库。"
                : "当前连接格式无法转换为表单，请填写新的 MySQL 连接；正在使用的配置继续保留。";
    }
    function invalidateCandidate() {
        candidateChecked = false;
        el("candidate-results").hidden = true;
        el("connection-tables").replaceChildren();
        el("schema-preview").hidden = true; el("schema-preview").replaceChildren();
        el("grant-output").hidden = true; el("grant-sql").textContent = "";
    }
    function selectedTables() { return [...el("connection-tables").querySelectorAll("input:checked")].map(i => i.value); }
    function connectionBody(tables) {
        return {host:el("db-host").value,port:Number(el("db-port").value),catalog:el("db-catalog").value,
            username:el("db-username").value,password:el("db-password").value,clearPassword:el("db-clear-password").checked,
            requireTls:el("db-tls").checked,tables};
    }
    const privilegeText = { READ_ONLY: "直接授权仅包含读取权限。", EXTRA_PRIVILEGES: "账号拥有额外权限，请换成专用只读账号再保存。",
        UNKNOWN: "无法确认完整权限，请核对直接授权和角色后使用专用只读账号。" };
    function candidateResult(result) {
        if (typeof result?.account !== "string" || typeof result.catalog !== "string"
            || !Array.isArray(result.tables) || !result.tables.every(t => typeof t === "string")
            || typeof result.truncated !== "boolean" || !Array.isArray(result.previews)
            || !Object.hasOwn(privilegeText,result.privileges)) throw new Error("候选连接响应格式不正确。");
        return result;
    }
    function previewSchemas(previews) {
        const blocks = previews.map(table => {
            if (typeof table?.name !== "string" || !Array.isArray(table.columns) || !table.columns.every(c => typeof c.name === "string"
                && typeof c.type === "string" && typeof c.nullable === "boolean")) throw new Error("表结构响应格式不正确。");
            const details = document.createElement("details"), summary = document.createElement("summary");
            summary.textContent = `${table.name} · ${table.columns.length} 个字段`; details.append(summary); details.open = true;
            const scroll = document.createElement("div"); scroll.className = "schema-scroll";
            scroll.tabIndex = 0; scroll.setAttribute("aria-label",table.name + " 表结构");
            const grid = document.createElement("table"), head = document.createElement("thead"), header = document.createElement("tr");
            for (const title of ["字段", "类型", "允许空值"]) { const th = document.createElement("th"); th.scope = "col"; th.textContent = title; header.append(th); }
            head.append(header); grid.append(head);
            const body = document.createElement("tbody");
            for (const c of table.columns) { const row = document.createElement("tr");
                for (const text of [c.name,c.type,c.nullable ? "是" : "否"]) { const td = document.createElement("td"); td.textContent = text; row.append(td); }
                body.append(row);
            }
            grid.append(body); scroll.append(grid); details.append(scroll); return details;
        });
        el("schema-preview").replaceChildren(...blocks); el("schema-preview").hidden = false;
    }
    async function writeDatabase(mode) {
        if (databaseBusy || !databaseView || !loaded || !el("connection-form").reportValidity()) return;
        const tables = mode === "test" ? [] : selectedTables();
        if (mode !== "test" && (!candidateChecked || !tables.length || tables.length > 50)) {
            databaseFeedback("请先测试连接，再选择 1～50 张业务表。", "error"); return;
        }
        const version = generation, body = connectionBody(tables);
        const previous = selectedTables();
        setDatabaseBusy(true);
        el("schema-preview").hidden = true;
        if (mode === "test") invalidateCandidate();
        databaseFeedback(mode === "save" ? "正在重新检查选中的表并保存连接…" : "正在检查候选连接与表结构…");
        try {
            const result = await post(mode === "save" ? "/api/settings/database" : "/api/settings/database/test",
                { headers:{"Content-Type":"application/json"},body:JSON.stringify(body) });
            if (version !== generation || !loaded) return;
            if (mode === "save") {
                displayConnection(result); invalidateCandidate(); databaseView.connection = result;
                databaseFeedback(result.pendingRestart ? "连接已保存，待重启服务后启用。当前问数继续使用原配置。" : "连接已保存，与当前使用的配置一致。", "success");
            } else {
                candidateResult(result);
                el("candidate-account").textContent = `候选账号：${result.account} · 数据库：${result.catalog}`;
                el("candidate-privileges").textContent = privilegeText[result.privileges];
                el("candidate-limit").textContent = result.truncated ? "最多展示前 200 张物理表，已截断；仅支持列表中的规范表名。" : "仅列出账号可见、名称规范的物理表；不包含视图。";
                if (mode === "test") {
                    const preferred = databaseView.connection.saved || databaseView.connection.active;
                    const chosen = previous.length ? previous : preferred?.catalog === result.catalog ? preferred.tables : [];
                    el("connection-tables").replaceChildren(...result.tables.map(table => checkbox(table,chosen.includes(table),
                        databaseView.accountTables?.includes(result.catalog + "." + table) ? " · 当前登录账号已授权" : " · 当前登录账号未授权")));
                    renderGrantTables(result.tables);
                } else previewSchemas(result.previews);
                candidateChecked = true;
                el("candidate-results").hidden = false;
                databaseFeedback(result.tables.length ? "候选连接检查完成，未保存。选表后可预览结构或保存。" : "连接成功，但未发现可选择的物理表，请检查库名与账号权限。", "success");
            }
        } catch (error) { if (version === generation) databaseFailure(error); }
        finally { if (version === generation) setDatabaseBusy(false); }
    }
    el("test-database").addEventListener("click", () => void writeDatabase("test"));
    el("preview-database").addEventListener("click", () => void writeDatabase("preview"));
    el("connection-form").addEventListener("submit", event => { event.preventDefault(); void writeDatabase("save"); });
    for (const id of ["db-host","db-port","db-catalog","db-username","db-password","db-tls","db-clear-password"]) {
        el(id).addEventListener("input", () => {
            invalidateCandidate();
            renderGrantTables(el("db-catalog").value === databaseView?.catalog ? databaseView.tables : []);
        });
    }
    el("db-clear-password").addEventListener("change", () => {
        el("db-password").value = ""; el("db-password").disabled = el("db-clear-password").checked;
    });
    el("connection-tables").addEventListener("change", () => { el("schema-preview").hidden = true; });
    el("check-database").addEventListener("click", async () => {
        if (databaseBusy || !databaseView || !loaded) return;
        const version = generation;
        setDatabaseBusy(true);
        el("database-results").hidden = true;
        databaseFeedback("正在检查连接与读取权限…");
        try {
            const result = await post("/api/settings/database/check");
            if (version !== generation || !loaded) return;
            if (typeof result?.account !== "string" || typeof result.catalog !== "string" || !Array.isArray(result.tables)
                || !["READ_ONLY", "EXTRA_PRIVILEGES", "UNKNOWN"].includes(result.privileges)
                || !result.tables.every(t => typeof t.table === "string" && typeof t.databaseReadable === "boolean"
                    && typeof t.applicationAllowed === "boolean")) throw new Error("数据库诊断响应格式不正确。");
            el("database-account").textContent = `连接账号：${result.account} · 数据库：${result.catalog}`;
            el("database-privileges").textContent = { READ_ONLY: "直接授权仅包含读取权限。",
                EXTRA_PRIVILEGES: "账号拥有额外权限，建议换成专用只读账号。", UNKNOWN: "无法确认完整权限，请在数据库管理工具中核对账号与角色。" }[result.privileges];
            el("database-tables").replaceChildren(...result.tables.map(table => {
                const item = document.createElement("li");
                item.textContent = `${table.table} · 数据库：${table.databaseReadable ? "可读取" : "不可读取"} · 当前登录账号：${table.applicationAllowed ? "已授权" : "未授权"}`;
                return item;
            }));
            el("database-results").hidden = false;
            databaseFeedback("检查完成。数据库读取权限与应用表授权已分别列出。", "success");
        } catch (error) { if (version === generation) databaseFailure(error); }
        finally { if (version === generation) setDatabaseBusy(false); }
    });
    el("grant-form").addEventListener("submit", async event => {
        event.preventDefault();
        if (databaseBusy || !databaseView || !loaded || !el("grant-form").reportValidity()) return;
        const version = generation;
        const tables = [...el("grant-tables").querySelectorAll("input:checked")].map(input => input.value);
        if (!tables.length) { databaseFeedback("至少选择一张业务表。", "error"); return; }
        setDatabaseBusy(true);
        el("grant-output").hidden = true;
        el("grant-sql").textContent = "";
        const body = { catalog: el("db-catalog").value, username: el("reader-name").value,
            host: el("reader-host").value, tables };
        try {
            const result = await post("/api/settings/database/grants", { headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
            if (version !== generation || !loaded) return;
            if (typeof result?.sql !== "string") throw new Error("授权脚本响应格式不正确。");
            el("grant-sql").textContent = result.sql;
            el("copy-grants").textContent = "复制 SQL";
            el("grant-output").hidden = false;
            databaseFeedback("脚本已生成。请替换密码占位符后，在数据库管理工具中执行。", "success");
        } catch (error) { if (version === generation) databaseFailure(error); }
        finally { if (version === generation) setDatabaseBusy(false); }
    });
    el("copy-grants").addEventListener("click", async () => {
        try { await navigator.clipboard.writeText(el("grant-sql").textContent); el("copy-grants").textContent = "已复制"; }
        catch { el("copy-grants").textContent = "复制失败，请选中 SQL 复制"; }
    });
    async function write(testOnly) {
        if (busy || !loaded || !el("model-form").reportValidity()) return;
        const version = generation;
        const body = { model: el("model-name").value, apiKey: el("api-key").value,
            timeoutSeconds: Number(el("timeout").value), clearApiKey: el("clear-key").checked };
        setBusy(true);
        feedback(testOnly ? "正在验证密钥与模型…" : "正在保存配置…");
        try {
            const result = await post(testOnly ? "/api/settings/model/test" : "/api/settings/model",
                { headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
            if (version !== generation || !loaded) return;
            if (testOnly) {
                if (typeof result?.message !== "string" || !Array.isArray(result.models)) throw new Error("连接测试响应格式不正确。");
                el("model-options").replaceChildren(...result.models.filter((m) => typeof m === "string").map((m) => {
                    const option = document.createElement("option"); option.value = m; return option;
                }));
                feedback(result.message, "success");
            } else {
                display(result);
                feedback("配置已保存并启用。下一次问数将使用这组设置。", "success");
            }
        } catch (error) { if (version === generation) failure(error); }
        finally { if (version === generation) setBusy(false); }
    }
    el("model-form").addEventListener("submit", (event) => { event.preventDefault(); void write(false); });
    el("test-model").addEventListener("click", () => void write(true));
    el("clear-key").addEventListener("change", () => {
        el("api-key").value = "";
        el("api-key").disabled = el("clear-key").checked;
    });
    window.addEventListener("pagehide", () => { generation++; el("api-key").value = ""; el("db-password").value = ""; });
    window.addEventListener("pageshow", (event) => { if (event.persisted) void load(); });
    el("check-configuration").addEventListener("click", async () => {
        const version = generation;
        const button = el("check-configuration");
        const panel = el("startup-diagnostics");
        if (button.disabled) return;
        button.disabled = true;
        panel.hidden = false;
        panel.textContent = "正在检查配置…";
        try {
            const report = await requestJson("/api/system/diagnostics", {}, 30000);
            if (version !== generation) return;
            const checks = [["模型", report?.model], ["数据库", report?.database], ["本地历史", report?.history]];
            if (checks.some(([, check]) => typeof check?.status !== "string" || typeof check.message !== "string")) {
                throw new Error("诊断响应格式不正确，请重试。");
            }
            panel.replaceChildren(...checks.map(([label, check]) => {
                const row = document.createElement("div");
                row.className = "diagnostic-row";
                row.dataset.status = check.status;
                const heading = document.createElement("strong"); heading.textContent = label;
                const message = document.createElement("p"); message.textContent = check.message;
                row.append(heading, message);
                return row;
            }));
        } catch (error) {
            if (version !== generation) return;
            if (error.status === 401) { window.location.replace(loginUrl("expired")); return; }
            panel.textContent = error.message || "配置检查未完成，请重试。";
        } finally { if (version === generation) button.disabled = false; }
    });
    void load();
})();
