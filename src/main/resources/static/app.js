/* 问数页面交互 */
"use strict";

(() => {
    const el = (id) => document.getElementById(id);
    const state = { busy: false, policy: null, answer: null, page: 0, user: null, initializing: false,
        noTables: false, turns: [], activeTurn: null, nextTurn: 0, lastStatus: null, schemaFingerprint: null, generation: 0,
        conversationId: null, activeResource: null, regenerateRequested: false,
        conversations: [], conversationOffset: 0, turnOffset: 0, historyVersion: 0, requestId: null, runViewId: null, stopping: false };
    const { RequestError, requestJson, post, loginUrl } = window.SqlfinderAuth;
    const PAGE_SIZE = 50;
    const charts = window.SqlfinderCharts?.create();
    const statuses = {
        NEEDS_CLARIFICATION: ["warning", "还需要一点信息"],
        SQL_VALIDATED: ["info", "SQL 已校验，尚未执行"],
        SQL_REJECTED: ["error", "查询未完成"],
        QUERY_SUCCEEDED: ["success", "查询完成"],
        QUERY_FAILED: ["error", "查询未完成"]
    };

    function requireLogin(reason = "expired") {
        forgetHistory();
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
        state.lastStatus = [tone, title, message, code, requestId, seconds];
        el("answer-identity").hidden = false;
        el("status-banner").hidden = false;
        el("status-banner").className = `status-banner ${tone}`;
        el("status-title").textContent = title;
        el("message").textContent = message;
        el("message").hidden = !message || /^(?:查询成功|查询完成|查询失败|查询未完成|处理完成)[。.!！]?$/.test(message.trim());
        el("error-detail").textContent = [code && `错误码：${code}`, requestId && `请求标识：${requestId}`].filter(Boolean).join(" · ");
        el("error-detail").hidden = !code && !requestId;
        el("elapsed").textContent = seconds ? `用时 ${seconds} s` : "";
    }

    function updateSubmitLabel() {
        el("submit-label").textContent = state.busy ? "正在处理…"
            : state.policy ? (state.policy.executionEnabled ? "查询数据" : "生成 SQL") : "提交问题";
    }

    function updateContextLabel() {
        const followUp = Boolean(state.activeResource) && !state.regenerateRequested;
        const regenerate = Boolean(state.activeResource) && state.regenerateRequested;
        el("question").placeholder = regenerate ? "重新生成这个问题…" : followUp ? "继续提问…" : "向你的数据提问…";
        el("question").setAttribute("aria-label", regenerate ? "重新生成的问题" : followUp ? "继续提问" : "新的问题");
    }

    function setBusy(busy) {
        state.busy = busy;
        el("stop-button").hidden = !busy;
        el("stop-button").disabled = state.stopping;
        for (const id of ["submit-button", "reset-button", "question"]) el(id).disabled = busy || state.initializing || !state.user;
        el("submit-button").disabled = busy || state.initializing || !state.user || state.noTables;
        el("regenerate-button").disabled = busy || state.initializing || !state.user || state.noTables;
        el("logout-button").disabled = busy || state.initializing || !state.user;
        el("refresh-config").disabled = busy || state.initializing;
        document.querySelectorAll(".example-button").forEach((button) => { button.disabled = busy || !state.user || state.noTables; });
        for (const id of ["refresh-history", "more-conversations", "earlier-turns"]) el(id).disabled = busy || state.initializing || !state.user || state.noTables;
        document.querySelectorAll(".past-view, .conversation-item").forEach((button) => {
            button.disabled = busy || state.initializing || !state.user || state.noTables;
        });
        document.querySelector(".answer-panel").setAttribute("aria-busy", String(busy));
        updateSubmitLabel();
    }

    async function stopRequest() {
        const id = state.requestId;
        if (!state.busy || !id || state.stopping) return;
        state.stopping = true;
        el("stop-button").disabled = true;
        try {
            // 请求刚发出时，后端可能尚未登记；短暂重试同一个取消。
            for (let attempt = 0; attempt < 8; attempt++) {
                try { await post(`/api/chat/requests/${id}/cancel`); break; }
                catch (error) {
                    if (error.status !== 404 || attempt === 7) throw error;
                    await new Promise(resolve => setTimeout(resolve, 150));
                }
            }
            if (state.requestId === id && state.busy) showStatus("pending", "正在停止", "正在结束本次请求。", "", id);
        } catch (error) {
            if (state.requestId === id && state.busy) {
                showStatus("warning", "停止未完成", error.message || "请再次尝试停止。", error.code, id);
                state.stopping = false;
                el("stop-button").disabled = false;
            }
        }
    }
    el("stop-button").addEventListener("click", stopRequest);

    const stages = { STARTING: "准备中", REQUEST: "检查请求", METADATA: "读取数据结构", CONTEXT: "读取会话", PROMPT: "准备问题",
        MODEL_REQUEST: "正在生成答案", MODEL_OUTPUT: "解析答案", VALIDATION: "校验查询", EXECUTION: "正在查询数据", HISTORY: "保存回答" };
    async function updateRun(id, generation, username, live = false) {
        try {
            const run = await requestJson(`/api/chat/requests/${id}`, {}, 5000);
            if (state.runViewId !== id || generation !== state.generation || username !== state.user?.username
                || (live && state.requestId !== id)) return;
            if (live && run.status === "RUNNING" && !state.stopping) {
                showStatus("pending", stages[run.stage] || "正在处理你的问题", "", "", "", (run.durationMs / 1000).toFixed(1));
            }
            const lines = [`请求标识：${id}`, `总耗时：${Number(run.durationMs || 0)} ms`];
            for (const [stage, duration] of Object.entries(run.stageDurations || {})) lines.push(`${stages[stage] || stage}：${Number(duration)} ms`);
            if (run.errorCode) lines.push(`原因：${run.errorCode}`);
            if (run.failureStage) lines.push(`中止阶段：${stages[run.failureStage] || run.failureStage}`);
            el("workflow-result").textContent = lines.join("\n");
            el("workflow-details").hidden = false;
        } catch { /* 运行详情不可用不影响答案。 */ }
    }

    function showNoTableAccess(message = "当前账号没有可用的数据表授权，请联系管理员配置。", code = "TABLE_ACCESS_DENIED", requestId = "") {
        forgetHistory();
        state.noTables = true;
        clearAnswer();
        el("source-tables").replaceChildren();
        el("source-summary").textContent = "当前账号没有可查询的授权表。";
        showStatus("warning", "需要数据表授权", message, code, requestId);
        setBusy(false);
    }

    function updateInput() {
        el("character-count").textContent = `${Array.from(el("question").value).length} 字`;
        el("question").removeAttribute("aria-invalid");
        el("question").style.height = "auto";
        el("question").style.height = `${Math.min(160, Math.max(72, el("question").scrollHeight))}px`;
    }

    function clearAnswer() {
        state.runViewId = null;
        charts?.clear();
        state.answer = null;
        state.page = 0;
        for (const id of ["sql-section", "table-section", "response-details", "workflow-details", "status-banner"]) el(id).hidden = true;
        el("response-details").open = false;
        el("sql-body").hidden = true;
        el("sql-toggle").setAttribute("aria-expanded", "false");
        el("sql-chevron").textContent = "⌄";
        el("current-question").hidden = true;
        el("question-message").textContent = "";
        el("answer-identity").hidden = true;
        el("regenerate-button").hidden = true;
        el("empty-state").hidden = false;
        el("result-filter").value = "";
        el("table-toolbar").hidden = false; el("table-view").hidden = false;
        el("result").textContent = "";
        el("sql").textContent = "";
        el("copy-sql").textContent = "复制 SQL";
        el("result-table").querySelector("thead").replaceChildren();
        el("result-table").querySelector("tbody").replaceChildren();
    }

    // 身份及授权变化时清除页面数据，持久化历史只从已授权的接口恢复。
    function forgetHistory(clearSaved = true) {
        state.generation++;
        state.turns = [];
        state.activeTurn = null;
        state.conversationId = null;
        state.activeResource = null;
        state.regenerateRequested = false;
        state.turnOffset = 0;
        el("earlier-turns").hidden = true;
        if (clearSaved) {
            state.historyVersion++;
            state.conversations = [];
            state.conversationOffset = 0;
            el("saved-conversations").replaceChildren();
            el("more-conversations").hidden = true;
            el("saved-note").textContent = "";
        } else renderConversations();
                el("past-messages").replaceChildren();
        el("later-messages").replaceChildren();
        el("question").value = "";
        updateInput();
        updateContextLabel();
        setBusy(false);
    }
    function showQuestion(question) {
        el("question-message").textContent = question;
        el("current-question").hidden = false;
    }
    function closeSidebar() {
        document.querySelector(".chat-shell").classList.remove("sidebar-open");
        el("sidebar-backdrop").hidden = true;
        el("sidebar-toggle").setAttribute("aria-expanded", "false");
        el("sidebar-toggle").setAttribute("aria-label", "打开导航");
        document.querySelector(".chat-main").inert = false;
    }
    function restoreTurn(turn, loading = false) {
        if ((!loading && (state.busy || state.initializing)) || !state.user || state.noTables || turn.username !== state.user.username) return;
        clearAnswer();
        state.activeTurn = turn.id;
        state.conversationId = turn.conversationId || null;
        state.activeResource = turn.contextAvailable === false ? null : turn.turnId || null;
        state.regenerateRequested = false;
        showQuestion(turn.question);
        if (turn.answer) renderAnswer(turn.answer, turn.seconds, turn.historical ? turn : null);
        else showStatus(...turn.status);
        renderHistory();
        renderConversations();
        el("question").value = "";
        updateInput();
        updateContextLabel();
        closeSidebar();
        el("current-question").scrollIntoView({ block: "start" });
    }
    function hasResultSnapshot(turn) {
        return turn.answer?.status === "QUERY_SUCCEEDED" && (!turn.historical
            || turn.resultSaved === true || turn.answer.rows.length > 0);
    }

    function archivedMessage(turn) {
        const article = node("article", "", "past-message");
        const question = node("div", "", "user-message"); question.append(node("p", turn.question));
        const answer = node("div", "", "past-answer");
        const heading = node("div", "", "past-answer-heading");
        heading.append(node("span", "S", "assistant-mark"), node("strong", "SQLFINDER"));
        if (turn.mode === "REGENERATE") heading.append(node("span", "重新生成", "past-result-note"));
        answer.append(heading, node("p", turn.status[1], "past-message-status"));
        if (turn.status[2] && !/^(?:查询成功|查询完成|查询失败|查询未完成|处理完成)[。.!！]?$/.test(turn.status[2].trim())) {
            answer.append(node("p", turn.status[2], "past-message-text"));
        }
        const data = turn.answer;
        if (hasResultSnapshot(turn)) {
            const count = data.rows.length;
            const previewLimit = 10;
            answer.append(node("p", `${count} 行${data.truncated ? " · 已截断" : ""}${turn.historical ? " · 已保存的历史结果" : ""}`, "past-result-note"));
            if (count) {
                const scroll = node("div", "", "past-table-scroll");
                scroll.tabIndex = 0; scroll.setAttribute("aria-label", "之前的查询结果，可横向滚动");
                const table = document.createElement("table");
                const caption = node("caption", turn.question + "的查询结果", "sr-only");
                const head = document.createElement("thead"), header = document.createElement("tr");
                for (const column of data.columns) { const th = node("th", column); th.scope = "col"; header.append(th); }
                head.append(header);
                const body = document.createElement("tbody");
                for (const row of data.rows.slice(0, previewLimit)) {
                    const tr = document.createElement("tr");
                    for (const column of data.columns) {
                        const value = cellValue(row, column);
                        const td = node("td", displayValue(value), value == null ? "null-value" : numericCell(column, value, data) ? "numeric" : "");
                        td.title = displayValue(value); tr.append(td);
                    }
                    body.append(tr);
                }
                table.append(caption, head, body); scroll.append(table); answer.append(scroll);
                if (count > previewLimit) answer.append(node("p", `这里显示前 ${previewLimit} 行，完整结果可在下方打开。`, "past-result-note"));
            } else answer.append(node("p", "查询成功，没有符合条件的数据。", "past-result-note"));
        } else if (data?.status === "QUERY_SUCCEEDED") {
            answer.append(node("p", `原查询返回 ${turn.rowCount} 行。旧记录只保存了摘要，没有结果快照。`, "past-result-note"));
        }
        if (data?.sql) {
            const details = document.createElement("details"); details.className = "past-sql";
            details.append(node("summary", "查看 SQL"), node("pre", data.sql)); answer.append(details);
        }
        if (turn.status[3] || turn.status[4]) answer.append(node("p", [turn.status[3], turn.status[4]].filter(Boolean).join(" · "), "past-result-note"));
        const view = node("button", hasResultSnapshot(turn) ? "查看完整结果与图表" : "查看这次记录", "past-view");
        view.type = "button"; view.addEventListener("click", () => restoreTurn(turn)); answer.append(view);
        view.setAttribute("aria-label", `查看回答：${turn.question}`);
        article.append(question, answer);
        return article;
    }

    function renderHistory() {
        const activeIndex = state.turns.findIndex(turn => turn.id === state.activeTurn);
        el("past-messages").replaceChildren(...state.turns.slice(0, activeIndex < 0 ? state.turns.length : activeIndex).map(archivedMessage));
        el("later-messages").replaceChildren(...(activeIndex < 0 ? [] : state.turns.slice(activeIndex + 1)).map(archivedMessage));
        setBusy(state.busy);
    }
    function rememberTurn(question, seconds) {
        if (!state.user || state.noTables) return;
        const turn = { id: ++state.nextTurn, username: state.user.username, question,
            answer: state.answer, seconds, status: [...state.lastStatus],
            conversationId: state.answer?.conversationId || state.conversationId,
            turnId: state.answer?.turnId || null,
            parentTurnId: state.answer?.parentTurnId || null,
            mode: state.answer?.mode || null };
        state.turns.push(turn);
        const limit = turn.turnId ? 50 : 10;
        if (state.turns.length > limit) {
            state.turns = state.turns.slice(-limit);
            if (turn.turnId) el("earlier-turns").hidden = false;
        }
        if (turn.turnId) state.turnOffset = state.turns.filter(item => item.turnId).length;
        state.activeTurn = turn.id;
        renderHistory();
    }

    function renderConversations() {
        el("saved-conversations").replaceChildren(...state.conversations.map(conversation => {
            const button = node("button", conversation.title, "conversation-item");
            button.type = "button";
            button.title = conversation.title;
            button.setAttribute("aria-current", String(conversation.id === state.conversationId));
            button.addEventListener("click", () => loadConversation(conversation.id));
            return button;
        }));
        setBusy(state.busy);
    }

    async function loadSavedConversations(more = false) {
        if (!state.user || state.noTables || !state.schemaFingerprint) return null;
        const generation = state.generation, username = state.user.username;
        const version = ++state.historyVersion;
        try {
            const data = await requestJson(`/api/chat/conversations?offset=${more ? state.conversationOffset : 0}`, {}, 10000);
            if (generation !== state.generation || username !== state.user?.username || version !== state.historyVersion) return null;
            if (!Array.isArray(data?.items) || !data.items.every(c => typeof c.id === "string" && typeof c.title === "string"))
                throw new RequestError("protocol", "历史列表格式不正确。");
            state.conversations = more ? [...state.conversations, ...data.items.filter(c => !state.conversations.some(old => old.id === c.id))] : data.items;
            state.conversationOffset = data.nextOffset;
            el("more-conversations").hidden = !data.hasMore;
            el("saved-note").textContent = state.conversations.length ? "仅展示当前授权范围的会话。" : "还没有保存的会话。";
            renderConversations();
            return state.conversations[0]?.id || null;
        } catch (error) {
            if (generation !== state.generation || username !== state.user?.username || version !== state.historyVersion) return null;
            if (error.status === 401) { requireLogin(); return null; }
            if (error.code === "TABLE_ACCESS_DENIED") { showNoTableAccess(error.message); return null; }
            el("saved-note").textContent = "历史读取失败，可点击刷新重试。";
            return null;
        }
    }

    async function loadConversation(id, more = false, initializing = false) {
        if (state.busy || (state.initializing && !initializing) || !state.user || state.noTables) return;
        const generation = state.generation, username = state.user.username;
        setBusy(true);
        try {
            const data = await requestJson(`/api/chat/conversations/${encodeURIComponent(id)}?offset=${more ? state.turnOffset : 0}`, {}, 10000);
            if (generation !== state.generation || username !== state.user?.username) return;
            if (data?.conversationId !== id || !Array.isArray(data.turns) || !data.turns.length)
                throw new RequestError("protocol", "历史会话格式不正确。");
            const turns = data.turns.map(item => {
                validateAnswer(item.answer);
                const [tone, title] = statuses[item.answer.status];
                return { id: ++state.nextTurn, username, question: item.answer.question,
                    answer: item.answer, status: [tone, title, item.answer.message || "处理完成。"],
                    conversationId: id, turnId: item.id, parentTurnId: item.parentTurnId,
                    historical: true, rowCount: item.rowCount, resultSaved: item.resultSaved === true,
                    createdAt: item.createdAt, contextAvailable: data.contextAvailable };
            });
            if (more) state.turns = [...turns, ...state.turns.filter(t => !turns.some(older => older.turnId === t.turnId))];
            else state.turns = turns;
            state.turnOffset = data.nextOffset;
            el("earlier-turns").hidden = !data.hasMore;
            restoreTurn(more ? state.turns.find(t => t.id === state.activeTurn) || state.turns.at(-1) : state.turns.at(-1), true);
        } catch (error) {
            if (generation !== state.generation || username !== state.user?.username) return;
            if (error.status === 401) { requireLogin(); return; }
            if (error.code === "TABLE_ACCESS_DENIED") { showNoTableAccess(error.message); return; }
            if (error.code === "CONTEXT_SCHEMA_CHANGED" || error.code === "CONTEXT_NOT_FOUND") {
                forgetHistory(); clearAnswer();
                await loadSavedConversations();
            }
            el("saved-note").textContent = error.message || "会话读取失败，请重试。";
        } finally {
            if (generation === state.generation && username === state.user?.username) setBusy(false);
        }
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

    function renderAnswer(data, seconds, historical = null) {
        validateAnswer(data);
        state.answer = data;
        if (historical && typeof data.requestId === "string" && /^[0-9a-f-]{36}$/i.test(data.requestId)) {
            state.runViewId = data.requestId;
            updateRun(data.requestId, state.generation, state.user.username);
        }
        if (data.conversationId) state.conversationId = data.conversationId;
        if (data.turnId && historical?.contextAvailable !== false) state.activeResource = data.turnId;
        state.regenerateRequested = false;
        el("empty-state").hidden = true;
        const [tone, title] = statuses[data.status];
        showStatus(tone, title, data.message || "处理完成。", data.errorCode || "", "", seconds);
        if (historical) {
            const resultNote = data.status === "QUERY_SUCCEEDED"
                ? hasResultSnapshot(historical)
                    ? `已保存当时返回的 ${data.rows.length} 行${data.truncated ? "（已截断）" : ""}，这是历史结果；重新查询可获取当前数据。`
                    : `原查询返回 ${historical.rowCount} 行${data.truncated ? "（已截断）" : ""}。完整结果未保存，重新生成并查询可查看当前数据。`
                : data.message || "处理完成。";
            showStatus(tone, `历史记录 · ${title}`, resultNote + (historical.contextAvailable ? "" : " 上下文已过期，请开始新对话。"), data.errorCode || "");
        }
        el("regenerate-button").hidden = !data.turnId || historical?.contextAvailable === false;
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
        if (data.status === "QUERY_SUCCEEDED" && (!historical || hasResultSnapshot(historical))) {
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
                const meta=Array.isArray(data.columnMetadata) ? data.columnMetadata.find(c => c?.name===column) : null;
                th.title = meta ? `${column} · ${meta.dataType} · 精度 ${meta.precision} / 小数位 ${meta.scale} · ${meta.nullable==null ? "空值属性未知" : meta.nullable ? "可空" : "非空"}` : column;
                header.append(th);
            }
            el("result-table").querySelector("thead").replaceChildren(header);
            renderTable();
            charts?.load(data);
            if (!charts) {
                el("chart-recommendation").textContent="图表组件未加载，可继续使用表格和 CSV。";
                document.querySelectorAll("[data-result-view]:not([data-result-view='table'])").forEach(b => {b.disabled=true;});
            }
        }
        updateContextLabel();
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
                const td = node("td", displayValue(value), value == null ? "null-value" : numericCell(column,value) ? "numeric" : "");
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

    function updateExamples(tables) {
        const available = new Set(tables.map(table => table.tableName));
        let examples;
        if (available.has("film")) {
            examples = [["电影总数", "一共有多少部电影？"],
                available.has("payment") ? ["每月收入", "按年月统计全部付款金额，按时间升序排列。"] : ["影片列表", "列出前十部影片的名称，按影片编号升序排列。"],
                available.has("rental") && available.has("category") && available.has("film_category") && available.has("inventory")
                    ? ["热门类别", "租借次数最多的五个影片类别是什么？返回类别名称和租借次数。"]
                    : ["影片时长", "统计影片的平均时长、最短时长和最长时长。"]];
        } else if (available.has("tb_shop")) {
            examples = [["商铺总数", "一共有多少家商铺？"], ["分类数量", "按商铺类型统计商铺数量，按类型 ID 升序排列。"],
                available.has("tb_shop_type") ? ["类型分布", "列出所有商铺类型的名称和商铺数量，包含没有商铺的类型。"]
                    : ["商铺列表", "列出前十家商铺，按编号升序排列。"]];
        } else {
            const first = tables[0]?.tableName;
            examples = first ? [["记录总数", `${first} 一共有多少条记录？`], ["查看记录", `列出 ${first} 的前十条记录。`],
                ["数据概览", `请概括 ${first} 中可以统计的数据，并说明需要补充的信息。`]] : [];
        }
        document.querySelectorAll(".example-button").forEach((button, index) => {
            const example = examples[index];
            button.hidden = !example;
            if (!example) return;
            button.dataset.question = example[1];
            button.querySelector("span").textContent = example[0];
        });
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
            el("mode-badge").textContent = enabled ? "查询模式" : "校验模式 · 仅生成 SQL";
            el("mode-badge").className = `mode-badge ${enabled ? "execution-mode" : "validation-mode"}`;
        } else {
            state.policy = null;
            el("connection-label").textContent = "配置读取失败";
            el("connection-dot").className = "connection-dot";
            el("mode-badge").textContent = "执行模式未知";
            el("mode-badge").className = "mode-badge";
        }
        updateSubmitLabel();
        el("source-tables").replaceChildren();
        const schemaResult = results[1];
        if (schemaResult.status === "fulfilled" && Array.isArray(schemaResult.value)) {
            const tables = schemaResult.value.filter((table) => table && typeof table.tableName === "string");
            updateExamples(tables);
            const fingerprint = JSON.stringify(tables);
            if (state.schemaFingerprint !== null && state.schemaFingerprint !== fingerprint) {
                forgetHistory(); clearAnswer();
            }
            state.schemaFingerprint = fingerprint;
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
        if (state.busy || state.initializing || !state.user || state.noTables) return;
        const question = el("question").value.trim();
        if (!question) {
            el("question").setAttribute("aria-invalid", "true");
            showStatus("warning", "先写下你的问题", "输入想查询的内容，或选择页面中的示例。");
            el("question").focus();
            return;
        }
        clearAnswer();
        state.requestId = crypto.randomUUID();
        state.runViewId = state.requestId;
        state.stopping = false;
        setBusy(true);
        state.activeTurn = null;
        renderHistory();
        showQuestion(question);
        el("empty-state").hidden = true;
        showStatus("pending", "正在处理你的问题", "正在生成并校验 SQL，请稍候。开启查询时还会读取数据。");
        el("current-question").scrollIntoView({ block: "start" });
        const started = performance.now();
        const generation = state.generation, username = state.user.username;
        const requestId = state.requestId;
        let polling = false;
        const runTimer = setInterval(async () => {
            if (polling) return;
            polling = true;
            try { await updateRun(requestId, generation, username, true); } finally { polling = false; }
        }, 1500);
        try {
            const data = await post("/api/chat/question", {
                headers: { "Content-Type": "application/json", "X-Request-Id": state.requestId },
                body: JSON.stringify({
                    question,
                    new_chat: !state.activeResource && !state.regenerateRequested,
                    resource: state.activeResource,
                    regenerated: state.regenerateRequested
                })
            }, Math.max(135000, (state.policy?.requestTimeoutSeconds || 120) * 1000 + 15000));
            if (generation !== state.generation || username !== state.user?.username) return;
            if (data.conversationId && state.conversationId && data.conversationId !== state.conversationId) {
                state.turns = [];
                state.turnOffset = 0;
                el("earlier-turns").hidden = true;
            }
            renderAnswer(data, ((performance.now() - started) / 1000).toFixed(1));
            if (["QUERY_SUCCEEDED", "SQL_VALIDATED"].includes(data.status)) {
                el("question").value = ""; updateInput();
            }
        } catch (error) {
            if (generation !== state.generation || username !== state.user?.username) return;
            if (error.status === 401) { requireLogin(); return; }
            if (error.code === "TABLE_ACCESS_DENIED") {
                showNoTableAccess(error.message, error.code, error.requestId);
                return;
            }
            if (typeof error.code === "string" && error.code.startsWith("CONTEXT_")) {
                state.conversationId = null;
                state.activeResource = null;
                state.regenerateRequested = false;
                updateContextLabel();
                showStatus("warning", "会话已失效", "当前上下文已清除，请从新对话开始。", error.code, error.requestId);
                return;
            }
            const known = error instanceof RequestError;
            if (error.code === "CLIENT_TIMEOUT") await stopRequest();
            showStatus(error.code === "REQUEST_CANCELLED" ? "info" : "error", error.code === "REQUEST_CANCELLED" ? "已停止"
                : known && error.kind === "network" ? "暂时无法获取答案" : "查询未完成",
                known ? error.message : "页面无法显示这次响应，请刷新后重试。", known ? error.code : "", known ? error.requestId : "");
        } finally {
            clearInterval(runTimer);
            if (generation === state.generation && username === state.user?.username) {
                updateRun(requestId, generation, username);
                if (!state.noTables) rememberTurn(question, ((performance.now() - started) / 1000).toFixed(1));
                state.regenerateRequested = false;
                updateContextLabel();
                state.requestId = null;
                state.stopping = false;
                setBusy(false);
                loadSavedConversations();
                (el("current-question").hidden ? el("status-banner") : el("current-question"))
                    .scrollIntoView({ block: "start", behavior: "auto" });
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
        forgetHistory(false);
        clearAnswer();
        el("question").value = "";
        updateInput();
        el("question").focus();
        closeSidebar();
    });
    el("regenerate-button").addEventListener("click", () => {
        const turn = state.turns.find(item => item.id === state.activeTurn);
        if (!turn?.turnId || state.busy) return;
        state.activeResource = turn.turnId;
        state.conversationId = turn.conversationId || state.conversationId;
        state.regenerateRequested = true;
        el("question").value = turn.question;
        updateInput();
        updateContextLabel();
        el("question-form").requestSubmit();
    });
    el("sql-toggle").addEventListener("click", () => {
        const expanded = el("sql-body").hidden;
        el("sql-body").hidden = !expanded;
        el("sql-toggle").setAttribute("aria-expanded", String(expanded));
        el("sql-chevron").textContent = expanded ? "⌃" : "⌄";
    });
    el("sidebar-toggle").addEventListener("click", () => {
        const open = el("sidebar-toggle").getAttribute("aria-expanded") !== "true";
        if (!open) { closeSidebar(); return; }
        document.querySelector(".chat-shell").classList.add("sidebar-open");
        el("sidebar-backdrop").hidden = false;
        el("sidebar-toggle").setAttribute("aria-expanded", "true");
        el("sidebar-toggle").setAttribute("aria-label", "关闭导航");
        document.querySelector(".chat-main").inert = true;
        (el("reset-button").disabled ? document.querySelector(".chat-brand") : el("reset-button")).focus();
    });
    el("sidebar-backdrop").addEventListener("click", () => { closeSidebar(); el("sidebar-toggle").focus(); });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && el("sidebar-toggle").getAttribute("aria-expanded") === "true") {
            closeSidebar(); el("sidebar-toggle").focus();
        }
        if (event.key === "Tab" && el("sidebar-toggle").getAttribute("aria-expanded") === "true") {
            const controls = [...el("chat-sidebar").querySelectorAll("a, button, summary")].filter(control => !control.disabled);
            const first = controls[0], last = controls.at(-1);
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
            if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }
    });
    window.matchMedia("(min-width: 761px)").addEventListener("change", (event) => { if (event.matches) closeSidebar(); });
    el("refresh-config").addEventListener("click", () => initialize());
    el("refresh-history").addEventListener("click", () => loadSavedConversations());
    el("more-conversations").addEventListener("click", () => loadSavedConversations(true));
    el("earlier-turns").addEventListener("click", () => loadConversation(state.conversationId, true));
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

    function numericCell(column,value,answer = state.answer) {
        return typeof value === "number" || typeof value === "string" && /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value)
            && Array.isArray(answer?.columnMetadata) && answer.columnMetadata.some(c => c?.name===column && ["INTEGER","DECIMAL","NUMBER"].includes(c.kind));
    }
    function csvCell(value, exactNumber = false) {
        let text = value == null ? "" : String(value);
        // 字符串或列名以公式标记开头时加单引号；数值负号不需要处理。
        if (!exactNumber && typeof value === "string" && /^[\s\uFEFF]*[=+@-]/.test(text)) text = "'" + text;
        return `"${text.replaceAll('"', '""')}"`;
    }

    el("export-csv").addEventListener("click", () => {
        const answer = state.answer;
        if (answer?.status !== "QUERY_SUCCEEDED" || !answer.rows.length) return;
        // 导出全部已返回的行，不受页面筛选或分页影响；不会再次查询数据库。
        const lines = [answer.columns.map(c => csvCell(c)).join(","), ...answer.rows.map((row) =>
            answer.columns.map((column) => csvCell(cellValue(row, column),numericCell(column,cellValue(row,column)))).join(","))];
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

    async function initialize(restoreLatest = false) {
        if (state.initializing) return;
        state.initializing = true;
        setBusy(state.busy);
        el("refresh-config").disabled = true;
        try {
            const user = await requestJson("/api/auth/me", {}, 10000);
            if (typeof user?.username !== "string" || !user.username) throw new RequestError("protocol", "无法确认当前用户，请刷新重试。");
            if (state.user && state.user.username !== user.username) { forgetHistory(); clearAnswer(); }
            state.user = user;
            el("current-user").textContent = user.username;
            el("current-user").title = user.username;
            el("user-controls").hidden = false;
            await loadConfig();
            const latest = await loadSavedConversations();
            if (restoreLatest && latest && !state.turns.length) await loadConversation(latest, false, true);
        } catch (error) {
            if (error.status === 401) { requireLogin(state.user ? "expired" : ""); return; }
            forgetHistory();
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
        if (event.persisted) { forgetHistory(); clearAnswer(); state.user = null; setBusy(false); initialize(true); }
    });
    setBusy(false);
    initialize(true);
})();
