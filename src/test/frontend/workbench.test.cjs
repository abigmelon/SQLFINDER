/* 问数工作台测试 */
const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const fs = require("node:fs/promises");
const path = require("node:path");
const { chromium } = require("playwright");

const staticRoot = path.resolve(__dirname, "../../main/resources/static");
const output = path.resolve(__dirname, "../../../output/playwright");
const policy = { executionEnabled: false, queryTimeoutSeconds: 5, maxResultRows: 1000 };
const schemas = [{ catalog: "mybatis", tableName: "tb_shop", comment: "商铺",
    columns: [{ name: "id", dataType: "BIGINT", comment: "编号" }] },
    { catalog: "mybatis", tableName: "tb_shop_type", columns: [] }];
const sql = "SELECT type_id, COUNT(*) AS shop_count\nFROM tb_shop\nGROUP BY type_id ORDER BY type_id";
const answer = (status = "QUERY_SUCCEEDED", extra = {}) => ({ status, question: "统计商铺",
    sql, columns: ["type_id", "shop_count"], rows: [{ type_id: 1, shop_count: 9 }, { type_id: 2, shop_count: 5 }],
    message: "查询成功。", validation: { tables: ["mybatis.tb_shop"], functions: ["COUNT"], policyVersion: "mysql-readonly-v1" },
    errorCode: null, truncated: false, ...extra });
let browser, server, origin;

test("停止发送带认证校验的取消请求，并等待后端确认", async t => {
    let finish, requestId, stops = 0;
    const { page, requests } = await pageFor(t, {
        onQuestion: (route, fulfill) => {
            requestId = route.request().headers()["x-request-id"];
            return new Promise(resolve => { finish = () => resolve(fulfill({ errorCode: "REQUEST_CANCELLED", message: "本次请求已停止。", requestId }, 409)); });
        },
        onRun: async (route, fulfill) => {
            if (route.request().method() === "GET") return fulfill({ requestId, status: "CANCELLED", durationMs: 100, stageDurations: { MODEL_REQUEST: 100 } });
            assert.equal(route.request().method(), "POST");
            assert.ok(route.request().headers()["x-csrf-token"]);
            assert.equal(new URL(route.request().url()).pathname, `/api/chat/requests/${requestId}/cancel`);
            stops++;
            await fulfill({ requestId, status: "CANCELLED" });
            finish();
        }
    });
    await page.locator("#question").fill("慢查询");
    await page.locator("#submit-button").click();
    await page.waitForFunction(() => !document.getElementById("stop-button").hidden);
    await page.waitForTimeout(100);
    assert.match(requestId, /^[0-9a-f-]{36}$/);
    await page.locator("#stop-button").click();
    await page.waitForFunction(() => !document.getElementById("submit-button").disabled);
    assert.equal(await page.locator("#status-title").innerText(), "已停止");
    assert.equal(await page.locator("#stop-button").isVisible(), false);
    assert.equal(stops, 1);
    assert.equal(requests.length, 1);
    assert.equal(await page.locator("#question").inputValue(), "慢查询");
});

test("总预算超时保留问题并展示后端原因", async t => {
    const { page } = await pageFor(t, { onQuestion: (route, fulfill) => fulfill({
        errorCode: "REQUEST_TIMEOUT", message: "本次请求超过总时限，请简化问题后重试。",
        requestId: route.request().headers()["x-request-id"]
    }, 408) });
    await submit(page, "一个很慢的问题");
    assert.equal(await page.locator("#status-title").innerText(), "查询未完成");
    assert.match(await page.locator("#message").innerText(), /总时限/);
    assert.match(await page.locator("#error-detail").innerText(), /REQUEST_TIMEOUT/);
    assert.equal(await page.locator("#question").inputValue(), "一个很慢的问题");
    assert.equal(await page.locator("#stop-button").isVisible(), false);
});

test("完成后可展开阶段耗时，详情失败不影响结果", async t => {
    const { page } = await pageFor(t, { onRun: (route, fulfill) => fulfill({
        status: "QUERY_SUCCEEDED", stage: "HISTORY", durationMs: 250,
        stageDurations: { MODEL_REQUEST: 180, EXECUTION: 50, HISTORY: 20 }
    }) });
    await submit(page);
    await page.waitForFunction(() => !document.getElementById("workflow-details").hidden);
    await page.locator("#workflow-details summary").click();
    assert.match(await page.locator("#workflow-result").innerText(), /正在查询数据：50 ms/);
    assert.equal(await page.locator("#status-title").innerText(), "查询完成");
    assert.equal(await page.locator("#table-section").isVisible(), true);
});

before(async () => {
    await fs.mkdir(output, { recursive: true });
    server = http.createServer(async (request, response) => {
        const pathname = new URL(request.url, "http://localhost").pathname;
        const assets = { "/": "index.html", "/index.html": "index.html", "/styles.css": "styles.css", "/app.js": "app.js",
            "/auth.js": "auth.js", "/login.html": "login.html", "/login.js": "login.js", "/chat.css": "chat.css",
            "/chart-data.js":"chart-data.js","/charts.js":"charts.js","/vendor/echarts.min.js":"vendor/echarts.min.js" };
        const file = assets[pathname];
        if (!file) { response.writeHead(404).end(); return; }
        try {
            const type = file.endsWith("html") ? "text/html" : file.endsWith("css") ? "text/css" : "text/javascript";
            response.writeHead(200, { "Content-Type": `${type}; charset=utf-8` });
            response.end(await fs.readFile(path.join(staticRoot, file)));
        } catch { response.writeHead(500).end(); }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    origin = `http://127.0.0.1:${server.address().port}`;
    browser = await chromium.launch({ headless: true,
        channel: process.env.SQLFINDER_BROWSER_CHANNEL || (process.platform === "win32" ? "msedge" : undefined) });
});

after(async () => {
    if (browser) await browser.close();
    if (server) await new Promise((resolve) => server.close(resolve));
});

async function pageFor(t, options = {}) {
    const context = await browser.newContext({ viewport: options.viewport || { width: 1440, height: 1000 },
        deviceScaleFactor: options.deviceScaleFactor || 1,
        reducedMotion: "reduce", acceptDownloads: true });
    const page = await context.newPage();
    const errors = [], requests = [], authRequests = [];
    let loggedIn = options.loggedIn ?? !options.startPath?.startsWith("/login.html"), tokenCount = 0;
    page.on("pageerror", (error) => errors.push(error.message));
    await context.route("**/api/**", async (route) => {
        const pathname = new URL(route.request().url()).pathname;
        const fulfill = (body, status = 200) => {
            if (status === 401 && pathname !== "/api/auth/login") loggedIn = false;
            return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
        };
        if (pathname === "/api/auth/me") {
            if (options.onMe) return options.onMe(route, fulfill);
            return loggedIn ? fulfill({ username: options.username || "test-reader" })
                : fulfill({ errorCode: "UNAUTHENTICATED", message: "请先登录。" }, 401);
        }
        if (pathname === "/api/auth/csrf") {
            ++tokenCount;
            if (options.onCsrf) return options.onCsrf(route, fulfill, tokenCount);
            return fulfill({ headerName: "X-CSRF-TOKEN", token: `test-csrf-${tokenCount}` });
        }
        if (pathname === "/api/auth/login" || pathname === "/api/auth/logout") {
            assert.equal(route.request().method(), "POST");
            assert.equal(route.request().headers()["x-csrf-token"], `test-csrf-${tokenCount}`);
            authRequests.push({ path: pathname, body: route.request().postData(), token: tokenCount });
            if (pathname.endsWith("login")) {
                assert.match(route.request().headers()["content-type"], /application\/x-www-form-urlencoded/);
                const loginResult = (body, status = 200) => { if (status === 200 && body.username) loggedIn = true; return fulfill(body, status); };
                if (options.onLogin) return options.onLogin(route, loginResult);
                loggedIn = true;
                return fulfill({ username: "test-reader" });
            }
            const logoutResult = (body, status = 200) => { if (status === 200) loggedIn = false; return fulfill(body, status); };
            if (options.onLogout) return options.onLogout(route, logoutResult);
            loggedIn = false;
            return fulfill({ message: "已退出登录。" });
        }
        if (pathname === "/api/chat/sql-policy") {
            if (options.onPolicy) return options.onPolicy(route, fulfill);
            return fulfill({ ...policy, executionEnabled: !!options.enabled });
        }
        if (pathname.startsWith("/api/chat/requests/")) {
            if (options.onRun) return options.onRun(route, fulfill);
            return fulfill({ errorCode: "REQUEST_NOT_FOUND", message: "请求不存在。" }, 404);
        }
        if (pathname.startsWith("/api/chat/conversations")) {
            if (options.onHistory) return options.onHistory(route, fulfill);
            return fulfill({ items: [], hasMore: false, nextOffset: 0 });
        }
        if (pathname === "/api/datasource/schema") {
            if (options.onSchema) return options.onSchema(route, fulfill);
            return fulfill(options.schemas || schemas, options.schemaStatus || 200);
        }
        if (pathname === "/api/chat/question") {
            assert.equal(route.request().method(), "POST");
            assert.match(route.request().headers()["content-type"], /application\/json/);
            assert.equal(route.request().headers()["x-csrf-token"], `test-csrf-${tokenCount}`);
            requests.push(route.request().postDataJSON());
            if (options.onQuestion) return options.onQuestion(route, fulfill);
            return fulfill(options.answer || answer());
        }
        // 出现新的请求时拒绝，确保测试不能漏出到真实 API。
        return route.abort();
    });
    t.after(async () => { await context.close(); assert.deepEqual(errors, [], "页面不应抛出 JavaScript 异常"); });
    await page.goto(origin + (options.startPath || "/"));
    await page.waitForFunction(() => document.getElementById("login-button")
        ? !document.getElementById("login-button").disabled : !document.getElementById("refresh-config").disabled);
    return { page, context, requests, authRequests, tokensRead: () => tokenCount };
}

async function submit(page, question = "统计商铺") {
    await page.locator("#question").fill(question);
    await page.locator("#submit-button").click();
    await page.waitForFunction(() => document.querySelector(".answer-panel").getAttribute("aria-busy") === "false"
        && !document.getElementById("status-banner").hidden);
}

test("对话布局：问题消息、底部输入、SQL 默认折叠并可展开", async t => {
    const { page } = await pageFor(t);
    await submit(page, "一共有多少家商铺？");
    assert.equal(await page.locator("#question-message").innerText(), "一共有多少家商铺？");
    assert.equal(await visible(page, "#sql-body"), false);
    assert.equal(await page.locator("#sql-toggle").getAttribute("aria-expanded"), "false");
    await page.locator("#sql-toggle").click();
    assert.equal(await visible(page, "#sql-body"), true);
    assert.equal(await page.locator("#sql").innerText(), sql);
    await page.locator("#sql-toggle").click();
    assert.equal(await visible(page, "#sql-body"), false);
    const composer = await page.locator(".composer-dock").boundingBox();
    assert.ok(composer.y > 650 && composer.y + composer.height <= 1000);
    assert.equal(await page.locator("#question").inputValue(), "");
});
test("本次查询切换恢复完整结果，不再次调用模型", async t => {
    let calls = 0;
    const { page, requests } = await pageFor(t, { onQuestion: (r, fulfill) => fulfill(++calls === 1 ? answer()
        : answer("NEEDS_CLARIFICATION", { sql: null, rows: [], columns: [], message: "请补充时间范围。" })) });
    await submit(page, "第一个问题");
    await submit(page, "第二个问题");
    assert.equal(await page.locator(".past-message").count(), 1);
    assert.equal(await page.locator(".past-message").count(), 1);
    await page.getByRole("button", { name: "查看回答：第一个问题", exact: true }).click();
    assert.equal(await page.locator("#question-message").innerText(), "第一个问题");
    assert.equal(await page.locator("tbody tr").count(), 2);
    assert.equal(await page.locator("#result-filter").inputValue(), "");
    await page.getByRole("button", { name: "查看回答：第二个问题", exact: true }).click();
    assert.equal(await visible(page, "#table-section"), false);
    assert.equal(await visible(page, "#sql-section"), false);
    assert.equal(requests.length, 2);
    await page.screenshot({ path: path.join(output, "chat-conversation-desktop.png"), fullPage: true });
});
test("新对话清除全部页面历史与数据", async t => {
    const { page } = await pageFor(t);
    await submit(page, "查询甲"); await submit(page, "查询乙");
    await page.locator("#reset-button").click();
    assert.equal(await page.locator(".past-message").count(), 0);
    assert.equal(await page.locator(".past-message").count(), 0);
    assert.equal(await visible(page, "#current-question"), false);
    assert.equal(await visible(page, "#empty-state"), true);
});
test("历史查询上限为十次，不写浏览器持久存储", async t => {
    const { page } = await pageFor(t);
    for (let i = 0; i < 11; i++) await submit(page, `私有测试问题${i}`);
    assert.equal(await page.locator(".past-message").count(), 9);
    assert.equal(await page.getByRole("button", { name: "查看回答：私有测试问题0", exact: true }).count(), 0);
    assert.equal(await page.evaluate(() => JSON.stringify([Object.entries(localStorage),Object.entries(sessionStorage)]).includes("私有测试问题")), false);
});
test("历史错误恢复保留错误码和请求标识", async t => {
    let calls=0;
    const { page, requests }=await pageFor(t,{ onQuestion:(r,fulfill)=> ++calls===1
        ? fulfill({ errorCode:"MODEL_REQUEST_FAILED",message:"连接失败",requestId:"history-error-id" },502) : fulfill(answer()) });
    await submit(page,"失败的查询"); await submit(page,"成功的查询");
    await page.getByRole("button", { name: "查看回答：失败的查询", exact: true }).click();
    assert.match(await page.locator("#error-detail").innerText(),/MODEL_REQUEST_FAILED.*history-error-id/);
    assert.equal(await visible(page,"#table-section"),false);
    assert.equal(requests.length,2);
});
test("刷新发现表范围变化清空先前查询记录", async t => {
    let changed=false;
    const { page }=await pageFor(t,{ onSchema:(r,fulfill)=>fulfill(changed?[schemas[1]]:schemas) });
    await submit(page); changed=true;
    await page.locator("#refresh-config").click();
    await page.waitForFunction(()=>!document.getElementById("refresh-config").disabled);
    assert.equal(await page.locator(".past-message").count(),0);
    assert.equal(await visible(page,"#table-section"),false);
});
test("历史问题和预览内容按文本显示", async t => {
    const malicious='<img src=x onerror="window.injected=true">';
    const { page }=await pageFor(t,{ answer:answer("NEEDS_CLARIFICATION",{sql:null,columns:[],rows:[],message:malicious}) });
    await submit(page,malicious); await submit(page,"另一个问题");
    assert.match(await page.locator("#past-messages").innerText(),/onerror/);
    assert.equal(await page.locator("img").count(),0);
    assert.equal(await page.evaluate(()=>window.injected),undefined);
});
test("手机导航支持焦点、Escape 和遮罩关闭", async t => {
    const { page }=await pageFor(t,{viewport:{width:390,height:844}});
    await page.locator("#sidebar-toggle").click();
    assert.equal(await visible(page,"#chat-sidebar"),true);
    assert.equal(await page.locator(".chat-main").evaluate(e=>e.inert),true);
    await page.keyboard.press("Escape");
    assert.equal(await page.locator(".chat-main").evaluate(e=>e.inert),false);
    assert.equal(await page.locator("#sidebar-toggle").evaluate(e=>e===document.activeElement),true);
    await page.locator("#sidebar-toggle").click();
    await page.locator("#sidebar-backdrop").click({position:{x:360,y:300}});
    assert.equal(await page.locator("#sidebar-toggle").getAttribute("aria-expanded"),"false");
});
test("手机在主区切换旧回答，输入框保持可见", async t => {
    const { page,requests }=await pageFor(t,{viewport:{width:320,height:800}});
    await submit(page,"甲"); await submit(page,"乙");
    await page.getByRole("button", { name: "查看回答：甲", exact: true }).click();
    assert.equal(await page.locator("#sidebar-toggle").getAttribute("aria-expanded"),"false");
    const composer=await page.locator(".composer-dock").boundingBox();
    assert.ok(composer.y+composer.height<=800);
    assert.equal(requests.length,2);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
});
test("缓存恢复后到达的旧响应不会重新显示旧问题或结果", async t => {
    let release;
    const pending=new Promise(resolve=>release=resolve);
    const {page}=await pageFor(t,{onQuestion:async(r,f)=>{await pending;return f(answer());}});
    await page.locator("#question").fill("旧问题");
    const sent=page.waitForRequest("**/api/chat/question");
    await page.locator("#submit-button").click(); await sent;
    await page.evaluate(()=>window.dispatchEvent(new PageTransitionEvent("pageshow",{persisted:true})));
    await page.waitForFunction(()=>!document.getElementById("refresh-config").disabled);
    const returned=page.waitForResponse("**/api/chat/question"); release(); await returned;
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    assert.equal(await visible(page,"#table-section"),false);
    assert.equal(await page.locator(".past-message").count(),0);
    assert.equal(await page.locator("#question").inputValue(),"");
});
const visible = (page, selector) => page.locator(selector).isVisible();
const savedId = "00000000-0000-0000-0000-000000000100";
const savedTurnId = "00000000-0000-0000-0000-000000000101";
const savedList = (title = "历史商铺统计") => ({ items: [{ id: savedId, title }], hasMore: false, nextOffset: 1 });
const savedDetail = (extra = {}) => ({ conversationId: savedId, title: "历史商铺统计", contextAvailable: true,
    turns: [{ id: savedTurnId, rowCount: 14, parentTurnId: null, answer: answer("QUERY_SUCCEEDED", {
        question: "历史商铺统计", rows: [], conversationId: savedId, turnId: savedTurnId, mode: "NEW" }) }],
    hasMore: false, nextOffset: 1, ...extra });

test("连续提问时保留上一条实际回答，切换旧回答仍能看到后续消息", async t => {
    let calls = 0, release;
    const pending = new Promise(resolve => release = resolve);
    const { page, requests } = await pageFor(t, { onQuestion: async (r, f) => {
        if (++calls === 2) await pending;
        return f(answer("QUERY_SUCCEEDED", { question: r.request().postDataJSON().question,
            conversationId: savedId, turnId: calls === 1 ? savedTurnId : "second-turn",
            columns: ["总数"], rows: [{ "总数": calls === 1 ? 1000 : 599 }] }));
    } });
    await submit(page, "电影总数");
    await page.locator("#question").fill("客户总数");
    const sent = page.waitForRequest("**/api/chat/question");
    await page.locator("#submit-button").click(); await sent;
    assert.match(await page.locator("#past-messages").innerText(), /电影总数[\s\S]*1000/);
    release();
    await page.waitForFunction(() => !document.getElementById("submit-button").disabled);
    assert.match(await page.locator("#past-messages").innerText(), /1000/);
    assert.equal(await page.locator("#result-table tbody td").last().innerText(), "599");
    await page.getByRole("button", { name: "查看回答：电影总数", exact: true }).click();
    assert.equal(await page.locator("#result-table tbody td").last().innerText(), "1000");
    assert.match(await page.locator("#later-messages").innerText(), /客户总数[\s\S]*599/);
    await page.locator("#later-messages .past-view").click();
    assert.equal(await page.locator("#question-message").innerText(), "客户总数");
    assert.match(await page.locator("#past-messages").innerText(), /1000/);
    assert.equal(requests.length, 2);
});

const savedSnapshots = () => savedDetail({ turns: [
    { id: savedTurnId, rowCount: 2, resultSaved: true, answer: answer("QUERY_SUCCEEDED", {
        question: "类别租借排行", conversationId: savedId, turnId: savedTurnId,
        columns: ["类别", "租借次数"], rows: [{ "类别": "Sports", "租借次数": 1179 }, { "类别": "Animation", "租借次数": 1166 }] }) },
    { id: "second-saved-turn", rowCount: 1, resultSaved: true, parentTurnId: savedTurnId,
        answer: answer("QUERY_SUCCEEDED", { question: "付款合计", conversationId: savedId, turnId: "second-saved-turn",
            columns: ["合计"], rows: [{ "合计": "67416.51" }],
            columnMetadata: [{ name: "合计", kind: "DECIMAL", encoding: "exact-string", dataType: "DECIMAL", precision: 20, scale: 2 }] }) }
] });

test("刷新恢复每轮结果，历史表格可导出并生成图表，不重放查询", async t => {
    const { page, requests } = await pageFor(t, { onHistory: (r, f) => f(new URL(r.request().url()).pathname.endsWith(savedId)
        ? savedSnapshots() : savedList("租赁经营分析")) });
    await page.waitForFunction(() => document.getElementById("question-message").textContent === "付款合计"
        && !document.getElementById("submit-button").disabled);
    assert.match(await page.locator("#past-messages").innerText(), /Sports[\s\S]*1179/);
    assert.equal(await page.locator("#result-table tbody td").last().innerText(), "67416.51");
    assert.match(await page.locator("#message").innerText(), /已保存当时返回的 1 行/);
    await page.reload();
    await page.waitForFunction(() => document.getElementById("question-message").textContent === "付款合计"
        && !document.getElementById("submit-button").disabled);
    await page.locator(".chat-scroll").evaluate(e => e.scrollTop = 0);
    assert.equal(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight), true);
    await page.screenshot({ path: path.join(output, "history-snapshots-desktop.png"), fullPage: true });
    const downloadPromise = page.waitForEvent("download");
    await page.locator("#export-csv").click();
    const download = await downloadPromise;
    assert.match(await fs.readFile(await download.path(), "utf8"), /67416\.51/);
    await page.locator("#past-messages .past-view").click();
    await chooseChart(page, "bar");
    assert.equal((await chartOptions(page)).series[0].data[0].label, "Sports");
    assert.match(await page.locator("#later-messages").innerText(), /67416\.51/);
    assert.equal(requests.length, 0);
    await page.locator(".chat-scroll").evaluate(e => e.scrollTop = 0);
    await page.screenshot({ path: path.join(output, "history-snapshots-chart.png"), fullPage: true });
});

test("保存的空结果能恢复为空表，过期只限制追问而不隐藏结果", async t => {
    const detail = savedDetail({ contextAvailable: false });
    detail.turns[0].resultSaved = true; detail.turns[0].rowCount = 0;
    const { page, requests } = await pageFor(t, { onHistory: (r, f) => f(new URL(r.request().url()).pathname.endsWith(savedId)
        ? detail : savedList()) });
    await page.waitForFunction(() => document.getElementById("message").textContent.includes("上下文已过期"));
    assert.equal(await visible(page, "#table-section"), true);
    assert.match(await page.locator("#table-empty").innerText(), /没有符合条件的数据/);
    assert.equal(await visible(page, "#regenerate-button"), false);
    assert.equal(requests.length, 0);
});

test("较长历史结果保留十行预览，打开后可查看全部已返回数据", async t => {
    let calls = 0;
    const { page, requests } = await pageFor(t, { onQuestion: (r, f) => f(++calls === 1
        ? answer("QUERY_SUCCEEDED", { columns: ["id"], rows: Array.from({ length: 55 }, (_, id) => ({ id })) })
        : answer()) });
    await submit(page, "库存明细"); await submit(page, "统计类别");
    assert.equal(await page.locator("#past-messages tbody tr").count(), 10);
    assert.match(await page.locator("#past-messages").innerText(), /55 行[\s\S]*前 10 行/);
    await page.locator("#past-messages .past-view").click();
    assert.equal(await page.locator("#result-table tbody tr").count(), 50);
    await page.locator("#next-page").click();
    assert.equal(await page.locator("#result-table tbody tr").count(), 5);
    assert.equal(requests.length, 2);
});

test("历史结果的列名、单元格和 SQL 均按文本展示，授权变化会清空全部消息", async t => {
    const malicious = '<img src=x onerror="window.snapshotInjected=true">';
    let changed = false;
    const detail = savedSnapshots();
    detail.turns[0].answer.columns = [malicious];
    detail.turns[0].answer.rows = [{ [malicious]: malicious }];
    detail.turns[0].answer.sql = malicious;
    const { page } = await pageFor(t, {
        onHistory: (r, f) => f(new URL(r.request().url()).pathname.endsWith(savedId) ? detail : savedList()),
        onSchema: (r, f) => f(changed ? [schemas[1]] : schemas)
    });
    await page.waitForFunction(() => document.getElementById("question-message").textContent === "付款合计"
        && !document.getElementById("submit-button").disabled);
    await page.locator("#past-messages details summary").click();
    assert.match(await page.locator("#past-messages").innerText(), /onerror/);
    assert.equal(await page.locator("#past-messages img").count(), 0);
    assert.equal(await page.evaluate(() => window.snapshotInjected), undefined);
    await page.locator("#past-messages .past-view").click();
    changed = true; await page.locator("#refresh-config").click();
    await page.waitForFunction(() => !document.getElementById("refresh-config").disabled);
    assert.equal(await page.locator("#past-messages .past-message, #later-messages .past-message").count(), 0);
    assert.equal(await visible(page, "#table-section"), false);
});

test("手机能连续查看历史结果，页面和输入框不会横向溢出", async t => {
    const { page, requests } = await pageFor(t, { viewport: { width: 390, height: 844 },
        onHistory: (r, f) => f(new URL(r.request().url()).pathname.endsWith(savedId) ? savedSnapshots() : savedList()) });
    await page.waitForFunction(() => document.getElementById("question-message").textContent === "付款合计"
        && !document.getElementById("submit-button").disabled);
    await page.locator(".chat-scroll").evaluate(e => e.scrollTop = 0);
    assert.match(await page.locator("#past-messages").innerText(), /1179/);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    assert.equal(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight), true);
    const composer = await page.locator(".composer-dock").boundingBox();
    assert.ok(composer.y + composer.height <= 844);
    await page.screenshot({ path: path.join(output, "history-snapshots-mobile.png"), fullPage: true });
    assert.equal(requests.length, 0);
});

test("空白提交不会清除已经显示的结果或会话", async t => {
    const { page, requests } = await pageFor(t);
    await submit(page);
    await page.locator("#submit-button").click();
    assert.equal(await visible(page, "#table-section"), true);
    assert.equal(await page.locator("#result-table tbody tr").count(), 2);
    assert.equal(await page.locator(".past-message").count(), 0);
    assert.equal(requests.length, 1);
});

test("刷新恢复历史摘要，继续追问携带原轮次，不自动执行查询", async t => {
    const { page, requests } = await pageFor(t, { onHistory: (r, f) => f(new URL(r.request().url()).pathname.endsWith(savedId)
        ? savedDetail() : savedList()), onQuestion: (r, f) => f(answer("QUERY_SUCCEEDED", {
            conversationId: savedId, turnId: "00000000-0000-0000-0000-000000000102", mode: "FOLLOW_UP" })) });
    await page.waitForFunction(() => document.getElementById("question-message").textContent === "历史商铺统计"
        && !document.getElementById("submit-button").disabled);
    assert.equal(requests.length, 0);
    assert.equal(await visible(page, "#table-section"), false);
    assert.match(await page.locator("#message").innerText(), /14 行.*完整结果未保存/);
    await page.screenshot({ path: path.join(output, "history-restored.png"), fullPage: true });
    await page.reload();
    await page.waitForFunction(() => document.getElementById("question-message").textContent === "历史商铺统计"
        && !document.getElementById("submit-button").disabled);
    await submit(page, "只看第一类");
    assert.deepEqual(requests[0], { question: "只看第一类", new_chat: false, resource: savedTurnId, regenerated: false });
    assert.equal(await visible(page, "#table-section"), true);
});

test("新对话保留历史列表并清除前置上下文", async t => {
    const { page, requests } = await pageFor(t, { onHistory: (r, f) => f(new URL(r.request().url()).pathname.endsWith(savedId)
        ? savedDetail() : savedList()) });
    await page.waitForFunction(() => !document.getElementById("reset-button").disabled);
    await page.locator("#reset-button").click();
    assert.equal(await page.locator(".conversation-item").count(), 1);
    assert.equal(await visible(page, "#current-question"), false);
    await submit(page, "新的问题");
    assert.equal(requests[0].new_chat, true);
    assert.equal(requests[0].resource, null);
});

test("过期历史仍可读，隐藏重新生成并提示新对话", async t => {
    const { page, requests } = await pageFor(t, { onHistory: (r, f) => f(new URL(r.request().url()).pathname.endsWith(savedId)
        ? savedDetail({ contextAvailable: false }) : savedList()) });
    await page.waitForFunction(() => document.getElementById("message").textContent.includes("上下文已过期"));
    assert.equal(await visible(page, "#regenerate-button"), false);
    await submit(page, "重新开始");
    assert.equal(requests[0].new_chat, true);
    assert.equal(requests[0].resource, null);
});

test("历史重新生成使用来源轮次，并展示新的实际结果", async t => {
    const { page, requests } = await pageFor(t, { onHistory: (r, f) => f(new URL(r.request().url()).pathname.endsWith(savedId)
        ? savedDetail() : savedList()) });
    await page.waitForFunction(() => !document.getElementById("regenerate-button").hidden
        && !document.getElementById("regenerate-button").disabled);
    await page.locator("#regenerate-button").click();
    await page.waitForFunction(() => !document.getElementById("submit-button").disabled);
    assert.equal(requests[0].regenerated, true);
    assert.equal(requests[0].resource, savedTurnId);
    assert.equal(await visible(page, "#table-section"), true);
});

test("历史列表和轮次分页不自动调用模型", async t => {
    const { page, requests } = await pageFor(t, { onHistory: (r, f) => {
        const url = new URL(r.request().url());
        if (url.pathname.endsWith(savedId)) return f(url.searchParams.get("offset") === "1"
            ? savedDetail({ turns: [{ id: "older-turn", rowCount: 0, answer: answer("SQL_VALIDATED", {
                question: "更早问题", rows: [], turnId: "older-turn", conversationId: savedId }) }], nextOffset: 2 })
            : savedDetail({ hasMore: true }));
        return f(url.searchParams.get("offset") === "1" ? { items: [{ id: "other-conversation", title: "另一会话" }], nextOffset: 2 }
            : { ...savedList(), hasMore: true });
    } });
    await page.waitForFunction(() => !document.getElementById("earlier-turns").disabled);
    await page.locator("#earlier-turns").click();
    await page.waitForFunction(() => document.querySelectorAll(".past-message").length === 1);
    await page.locator("#more-conversations").click();
    await page.waitForFunction(() => document.querySelectorAll(".conversation-item").length === 2);
    assert.equal(requests.length, 0);
});

test("历史中的 HTML 按文字显示且不能执行脚本", async t => {
    const text = '<img src=x onerror="window.historyInjected=true">';
    const detail = savedDetail(); detail.turns[0].answer.question = text;
    const { page } = await pageFor(t, { onHistory: (r, f) => f(new URL(r.request().url()).pathname.endsWith(savedId) ? detail : savedList(text)) });
    await page.waitForFunction(() => document.getElementById("question-message").textContent.startsWith("<img"));
    assert.equal(await page.locator("#saved-conversations img, #question-message img").count(), 0);
    assert.equal(await page.evaluate(() => window.historyInjected), undefined);
});

test("历史不可用不阻止新查询，也不会重放历史", async t => {
    const { page, requests } = await pageFor(t, { onHistory: (r, f) => f({ errorCode: "HISTORY_UNAVAILABLE", message: "暂不可用" }, 503) });
    assert.match(await page.locator("#saved-note").innerText(), /历史读取失败/);
    assert.equal(requests.length, 0);
    await submit(page);
    assert.equal(requests.length, 1);
});

const field=(name,kind)=>({name,kind,dataType:{TEXT:"VARCHAR",DECIMAL:"DECIMAL",INTEGER:"BIGINT",DATE:"DATE",DATETIME:"TIMESTAMP",BOOLEAN:"BOOLEAN"}[kind]||"DOUBLE",
    nullable:true,precision:kind==="DECIMAL"?38:20,scale:kind==="DECIMAL"?4:0,encoding:kind==="DECIMAL"?"exact-string":"number-or-exact-string"});
const chartAnswer=(rows,extra={})=>answer("QUERY_SUCCEEDED",{columns:["category","amount"],rows:rows.map(([category,amount])=>({category,amount})),
    columnMetadata:[field("category","TEXT"),field("amount","DECIMAL")],...extra});
async function chooseChart(page,type) {
    await page.locator(`[data-result-view="${type}"]`).click();
    await page.waitForFunction(()=>!document.getElementById("chart-panel").hidden);
}
async function chartOptions(page) {
    return page.evaluate(()=>window.echarts.getInstanceByDom(document.getElementById("chart-canvas"))?.getOption());
}
test("推荐柱状图真实绘制，切换不重复请求模型",async t=>{
    const {page,requests}=await pageFor(t,{answer:chartAnswer([["餐饮","9.00"],["零售","5.00"]])});
    await submit(page); assert.equal(await visible(page,"#table-view"),true);
    assert.match(await page.locator("#chart-recommendation").innerText(),/推荐柱状图/);
    await page.locator("#recommend-chart").click();
    await page.waitForFunction(()=>!!window.echarts.getInstanceByDom(document.getElementById("chart-canvas")));
    const options=await chartOptions(page);assert.equal(options.series[0].type,"bar");assert.equal(options.series[0].data[0].value,9);
    assert.equal(options.series[0].data[0].exact,"9.00");assert.equal(await page.locator("#chart-canvas canvas").count(),1);
    assert.equal(await visible(page,"#table-view"),false);
    await page.screenshot({path:path.join(output,"chart-bar-desktop.png")});
    await chooseChart(page,"pie");assert.equal((await chartOptions(page)).series[0].type,"pie");
    await page.screenshot({path:path.join(output,"chart-pie-desktop.png")});
    await page.locator('[data-result-view="table"]').click();assert.equal(await visible(page,"#result-table"),true);assert.equal(requests.length,1);
});
test("手动更换数值字段仅重新绘图，不改结果或发起查询",async t=>{
    const {page,requests}=await pageFor(t,{answer:chartAnswer([["A","9"],["B","5"]],{columns:["category","amount","count"],
        columnMetadata:[field("category","TEXT"),field("amount","DECIMAL"),field("count","INTEGER")],rows:[{category:"A",amount:"9",count:2},{category:"B",amount:"5",count:3}]})});
    await submit(page);await chooseChart(page,"bar");await page.locator("#chart-y").selectOption("count");
    assert.deepEqual((await chartOptions(page)).series[0].data.map(d=>d.value),[2,3]);assert.equal(requests.length,1);
    await page.locator('[data-result-view="table"]').click();assert.match(await page.locator("#result-table").innerText(),/9/);
});
test("日期折线排序并保留 NULL 断点和原始表格顺序",async t=>{
    const {page}=await pageFor(t,{answer:chartAnswer([["2026-10-12","2"],["2026-10-10","1"],["2026-10-11",null]],
        {columnMetadata:[field("category","DATE"),field("amount","DECIMAL")]})});
    await submit(page);assert.match(await page.locator("#chart-recommendation").innerText(),/推荐折线图/);await chooseChart(page,"line");
    const options=await chartOptions(page);assert.equal(options.series[0].connectNulls,false);
    assert.equal(options.useUTC,true);assert.equal(options.xAxis[0].type,"time");assert.equal(options.xAxis[0].minInterval,86400000);
    assert.equal(options.series[0].data[0].label,"2026-10-10");assert.equal(options.series[0].data[1].value[1],null);
    assert.match(await page.locator("#chart-note").innerText(),/断点/);
    await page.screenshot({path:path.join(output,"chart-line-desktop.png")});
    await page.locator('[data-result-view="table"]').click();assert.match(await page.locator("#result-table tbody tr").first().innerText(),/2026-10-12/);
});
test("截断结果允许柱形提示局部范围，但拒绝饼图",async t=>{
    const {page}=await pageFor(t,{answer:chartAnswer([["A","9"],["B","5"]],{truncated:true})});
    await submit(page);await chooseChart(page,"bar");assert.match(await page.locator("#chart-note").innerText(),/截断/);
    await chooseChart(page,"pie");assert.match(await page.locator("#chart-error").innerText(),/截断/);
    assert.equal(await chartOptions(page),undefined);assert.equal(await page.locator("#export-chart").isDisabled(),true);
});
test("巨大整数和高精度小数留在表格及 CSV，拒绝绘图",async t=>{
    const exact="18446744073709551615";
    const {page}=await pageFor(t,{answer:chartAnswer([["A",exact]],{columnMetadata:[field("category","TEXT"),field("amount","INTEGER")]})});
    await submit(page);assert.match(await page.locator("#result-table").innerText(),new RegExp(exact));
    await chooseChart(page,"bar");assert.match(await page.locator("#chart-error").innerText(),/安全范围/);
    const downloading=page.waitForEvent("download");await page.locator("#export-csv").click();
    const file=path.join(output,"chart-exact-values.csv");await (await downloading).saveAs(file);assert.match(await fs.readFile(file,"utf8"),new RegExp(exact));
});
test("分类重复不静默聚合，表格结果仍可查看",async t=>{
    const {page}=await pageFor(t,{answer:chartAnswer([["A","9"],["A","5"]])});
    await submit(page);await chooseChart(page,"bar");assert.match(await page.locator("#chart-error").innerText(),/分组和聚合/);
    await page.locator('[data-result-view="table"]').click();assert.equal(await page.locator("#result-table tbody tr").count(),2);
});
test("空结果、全 NULL 和分类折线有可操作原因",async t=>{
    let calls=0;
    const {page}=await pageFor(t,{onQuestion:(r,f)=>f(chartAnswer(++calls===1 ? [] : calls===2 ? [["A",null]] : [["A","1"],["B","2"]]))});
    await submit(page);await chooseChart(page,"bar");assert.match(await page.locator("#chart-error").innerText(),/没有返回/);
    await submit(page);await chooseChart(page,"bar");assert.match(await page.locator("#chart-error").innerText(),/全部为 NULL/);
    await submit(page);await chooseChart(page,"line");assert.match(await page.locator("#chart-error").innerText(),/分类文本/);
});
test("负值和零在柱形中保持原值，饼图拒绝负值",async t=>{
    const {page}=await pageFor(t,{answer:chartAnswer([["A","-5"],["B","0"],["C",null]])});
    await submit(page);await chooseChart(page,"bar");assert.deepEqual((await chartOptions(page)).series[0].data.map(d=>d.value),[-5,0,null]);
    await chooseChart(page,"pie");assert.match(await page.locator("#chart-error").innerText(),/负值/);
});
test("表格筛选和分页不会改变图表已返回数据范围",async t=>{
    const {page,requests}=await pageFor(t,{answer:chartAnswer(Array.from({length:60},(_,i)=>["类别"+i,"1"]))});
    await submit(page);await page.locator("#result-filter").fill("类别59");
    assert.equal(await page.locator("#result-table tbody tr").count(),1);
    await chooseChart(page,"bar");assert.equal((await chartOptions(page)).series[0].data.length,60);
    assert.match(await page.locator("#chart-note").innerText(),/不受表格筛选或分页/);assert.equal(requests.length,1);
});
test("历史记录切换重建对应图表，权限拒绝清理旧图表",async t=>{
    let calls=0;
    const {page,requests}=await pageFor(t,{onQuestion:(r,f)=>++calls===3 ? f({errorCode:"TABLE_ACCESS_DENIED",message:"授权失效"},403)
        : f(chartAnswer(calls===1 ? [["甲","9"]] : [["乙","5"]]))});
    await submit(page,"第一条");await chooseChart(page,"bar");await submit(page,"第二条");await chooseChart(page,"bar");
    assert.equal((await chartOptions(page)).series[0].data[0].label,"乙");
    await page.getByRole("button", { name: "查看回答：第一条", exact: true }).click();await chooseChart(page,"bar");
    assert.equal((await chartOptions(page)).series[0].data[0].label,"甲");assert.equal(requests.length,2);
    await submit(page,"第三条");assert.equal(await chartOptions(page),undefined);assert.equal(await visible(page,"#table-section"),false);
});
test("新对话销毁图表实例和字段选择",async t=>{
    const {page}=await pageFor(t,{answer:chartAnswer([["A","1"]])});await submit(page);await chooseChart(page,"bar");
    await page.locator("#reset-button").click();assert.equal(await chartOptions(page),undefined);
    assert.equal(await page.locator("#chart-y option").count(),0);assert.equal(await visible(page,"#chart-panel"),false);
});
test("字段名和分类中的 HTML 不成为 DOM 或脚本",async t=>{
    const label='<img src=x onerror="window.chartInjected=true">';
    const {page}=await pageFor(t,{answer:chartAnswer([[label,"9"],["B","5"]])});await submit(page);await chooseChart(page,"bar");
    assert.equal((await chartOptions(page)).series[0].data[0].label,label);
    assert.equal(await page.locator("#chart-panel img").count(),0);assert.equal(await page.evaluate(()=>!!window.chartInjected),false);
});
test("PNG 导出真实图片，CSV 不受图表选择影响",async t=>{
    const {page,requests}=await pageFor(t,{answer:chartAnswer([["餐饮","9.00"],["零售","5.00"]])});await submit(page);await chooseChart(page,"pie");
    assert.match((await chartOptions(page)).title[0].subtext,/占比仅限本次/);
    const downloading=page.waitForEvent("download");await page.locator("#export-chart").click();const download=await downloading;
    assert.match(download.suggestedFilename(),/^sqlfinder-pie-.*\.png$/);
    const file=path.join(output,"chart-export.png");await download.saveAs(file);const bytes=await fs.readFile(file);
    assert.equal(bytes.subarray(0,8).toString("hex"),"89504e470d0a1a0a");assert.equal(requests.length,1);
});
test("精确负小数 CSV 保留负号及小数位，同时仍防止公式注入",async t=>{
    const {page}=await pageFor(t,{answer:chartAnswer([["A","-5.00"],["B","-=SUM(1,2)"]])});await submit(page);
    assert.equal(await page.locator("#result-table tbody tr").first().locator("td.numeric").innerText(),"-5.00");
    const downloading=page.waitForEvent("download");await page.locator("#export-csv").click();
    const file=path.join(output,"chart-negative-values.csv");await (await downloading).saveAs(file);const text=await fs.readFile(file,"utf8");
    assert.match(text,/"-5\.00"/);assert.match(text,/"'-=SUM/);
});
test("真实图表提示使用富文本渲染，显示精确原值和本次占比",async t=>{
    const {page}=await pageFor(t,{answer:chartAnswer([["餐饮","9.0000"],["零售","5.0000"]])});await submit(page);await chooseChart(page,"pie");
    const tip=await page.evaluate(()=>{
        const chart=window.echarts.getInstanceByDom(document.getElementById("chart-canvas"));
        chart.dispatchAction({type:"showTip",seriesIndex:0,dataIndex:0});
        const o=chart.getOption();return {mode:o.tooltip[0].renderMode,text:o.tooltip[0].formatter({data:o.series[0].data[0],percent:64.29})};
    });
    assert.equal(tip.mode,"richText");assert.match(tip.text,/9\.0000/);assert.match(tip.text,/本次结果/);
});
for (const width of [320,390]) test(`图表 ${width}px 可切换和导出，无整页横向溢出`,async t=>{
    const {page}=await pageFor(t,{viewport:{width,height:844},answer:chartAnswer([["餐饮","9.00"],["零售","5.00"]])});
    await submit(page);await chooseChart(page,"bar");assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    const canvas=await page.locator("#chart-canvas").boundingBox(),dock=await page.locator(".composer-dock").boundingBox();
    assert.ok(canvas.y+canvas.height<=dock.y+1,"图表不应被底部输入区遮挡");
    assert.equal(await page.locator("#export-chart").isEnabled(),true);
    await page.screenshot({path:path.join(output,`chart-bar-mobile-${width}.png`)});
    await chooseChart(page,"pie");assert.equal((await chartOptions(page)).series[0].type,"pie");
});
test("组件加载失败保留表格和 CSV，提供图表失败说明",async t=>{
    const {page,context}=await pageFor(t,{answer:chartAnswer([["A","1"]])});
    await context.route("**/vendor/echarts.min.js",r=>r.abort());await page.reload();
    await page.waitForFunction(()=>!document.getElementById("submit-button").disabled);await submit(page);await chooseChart(page,"bar");
    assert.match(await page.locator("#chart-error").innerText(),/组件未加载/);
    await page.locator('[data-result-view="table"]').click();assert.equal(await visible(page,"#result-table"),true);assert.equal(await page.locator("#export-csv").isEnabled(),true);
});

test("校验模式读取真实字段形状，示例只填入问题，不自动请求", async (t) => {
    const { page, requests } = await pageFor(t);
    assert.match(await page.locator("#mode-badge").innerText(), /仅生成 SQL/);
    assert.equal(await page.locator("#submit-label").innerText(), "生成 SQL");
    assert.match(await page.locator("#source-summary").innerText(), /mybatis · 2 张授权表/);
    await page.locator(".source-table summary").first().click();
    assert.match(await page.locator(".source-table").first().innerText(), /id · BIGINT · 编号/);
    await page.locator(".example-button").first().click();
    assert.equal(await page.locator("#question").inputValue(), "一共有多少家商铺？");
    assert.equal(requests.length, 0);
    await page.screenshot({ path: path.join(output, "workbench-empty-desktop.png"), fullPage: true });
});

test("执行模式来自策略接口，简化标签并隐藏冗余限制说明", async (t) => {
    const { page } = await pageFor(t, { enabled: true });
    assert.equal(await page.locator("#submit-label").innerText(), "查询数据");
    assert.equal(await page.locator("#mode-badge").innerText(), "查询模式");
    assert.equal(await page.locator("#query-limits").count(), 0);
});

test("Sakila示例适配授权表，只有点提交才发起问数", async t => {
    const sampleSchemas = ["film", "payment", "rental", "category", "film_category", "inventory"].map(tableName => ({
        catalog: "sqlfinder_sakila", tableName, columns: [] }));
    const { page, requests } = await pageFor(t, { schemas: sampleSchemas, enabled: true });
    assert.equal(await page.locator("#query-history, #history-heading, #question-help, #mode-note").count(), 0);
    await page.locator(".example-button").first().click();
    assert.equal(await page.locator("#question").inputValue(), "一共有多少部电影？");
    assert.match(await page.locator(".example-button").nth(1).getAttribute("data-question"), /付款金额/);
    assert.match(await page.locator(".example-button").nth(2).getAttribute("data-question"), /影片类别/);
    assert.equal(requests.length, 0);
});

test("成功只显示一条状态，失败保留原因", async t => {
    let calls = 0;
    const { page } = await pageFor(t, { enabled: true, onQuestion: (r, f) => f(++calls === 1 ? answer()
        : answer("QUERY_FAILED", { message: "数据库查询超过时限，请缩小时间范围。", rows: [] })) });
    await submit(page);
    assert.equal(await page.locator("#status-title").innerText(), "查询完成");
    assert.equal(await page.locator("#message").isVisible(), false);
    await submit(page, "第二个问题");
    assert.equal(await page.locator("#status-title").innerText(), "查询未完成");
    assert.match(await page.locator("#message").innerText(), /缩小时间范围/);
    assert.equal(await page.locator("#past-messages .past-message-status").innerText(), "查询完成");
    assert.equal(await page.locator("#past-messages .past-message-text").count(), 0);
});

for (const screen of [
    { name: "desktop-960", width: 960, height: 920 },
    { name: "short-window", width: 960, height: 520 },
    { name: "mobile-390", width: 390, height: 844 },
    { name: "mobile-320", width: 320, height: 640 },
    { name: "zoom-200", width: 480, height: 460, scale: 2 }
]) {
    test(`新版连续回答布局无整页溢出：${screen.name}`, async t => {
        const { page } = await pageFor(t, { enabled: true, viewport: { width: screen.width, height: screen.height },
            deviceScaleFactor: screen.scale || 1 });
        await submit(page, "统计各类商铺数量");
        await submit(page, "只显示前五类");
        assert.equal(await page.locator(".past-message").count(), 1);
        const dimensions = await page.evaluate(() => ({ height: document.documentElement.scrollHeight, width: document.documentElement.scrollWidth,
            viewportHeight: innerHeight, viewportWidth: innerWidth }));
        assert.ok(dimensions.height <= dimensions.viewportHeight, JSON.stringify(dimensions));
        assert.ok(dimensions.width <= dimensions.viewportWidth, JSON.stringify(dimensions));
        const composer = await page.locator(".composer-dock").boundingBox();
        const submitBox = await page.locator("#submit-button").boundingBox();
        assert.ok(composer.y + composer.height <= screen.height);
        assert.ok(submitBox.y >= 0 && submitBox.y + submitBox.height <= screen.height);
        assert.equal(await page.locator("#chat-sidebar #query-history").count(), 0);
        await page.screenshot({ path: path.join(output, `ui-${screen.name}.png`), fullPage: true });
    });
}

test("登录页精简深色且960px和短窗口可用", async t => {
    const { page } = await pageFor(t, { startPath: "/login.html", viewport: { width: 960, height: 920 } });
    assert.equal(await page.locator(".login-intro, .login-description, .login-support").count(), 0);
    assert.equal(await page.locator("#login-button").innerText(), "登录\n↗");
    assert.equal(await page.locator("body").evaluate(element => getComputedStyle(element).backgroundColor), "rgb(25, 26, 28)");
    await page.screenshot({ path: path.join(output, "ui-login-960.png"), fullPage: true });
    await page.setViewportSize({ width: 960, height: 520 });
    await page.locator("#login-button").scrollIntoViewIfNeeded();
    assert.equal(await page.locator("#login-button").isVisible(), true);
    await page.screenshot({ path: path.join(output, "ui-login-short.png"), fullPage: true });
});

test("空白问题本地拦截，恢复输入焦点", async (t) => {
    const { page, requests } = await pageFor(t);
    await submit(page, "   ");
    assert.equal(requests.length, 0);
    assert.equal(await page.locator("#question").getAttribute("aria-invalid"), "true");
    assert.equal(await page.locator("#question").evaluate((element) => element === document.activeElement), true);
});

test("SQL_VALIDATED 展示 SQL 和摘要，不伪造结果表格", async (t) => {
    const { page, requests } = await pageFor(t, { answer: answer("SQL_VALIDATED", { rows: [], columns: [], message: "SQL 已通过规则检查，尚未执行。" }) });
    await submit(page, "  统计商铺  ");
    assert.deepEqual(requests, [{ question: "统计商铺", new_chat: true, resource: null, regenerated: false }]);
    assert.equal(await page.locator("#sql").innerText(), sql);
    assert.match(await page.locator("#validation-tables").innerText(), /mybatis.tb_shop/);
    assert.equal(await page.locator("#validation-functions").innerText(), "COUNT");
    assert.equal(await visible(page, "#table-section"), false);
    assert.match(await page.locator("#status-title").innerText(), /尚未执行/);
    await page.screenshot({ path: path.join(output, "workbench-validated-desktop.png"), fullPage: true });
});

test("新对话、继续追问和新对话重置使用不同的上下文字段", async (t) => {
    let calls = 0;
    const { page, requests } = await pageFor(t, {
        onQuestion: (route, fulfill) => {
            calls++;
            return fulfill(answer("SQL_VALIDATED", {
                conversationId: "conversation-1",
                turnId: `turn-${calls}`,
                parentTurnId: calls === 1 ? null : "turn-1",
                mode: calls === 1 ? "NEW" : "FOLLOW_UP"
            }));
        }
    });
    await submit(page, "第一个问题");
    await submit(page, "继续追问");
    assert.equal(requests[0].new_chat, true);
    assert.equal(requests[0].resource, null);
    assert.equal(requests[1].new_chat, false);
    assert.equal(requests[1].resource, "turn-1");
    assert.equal(requests[1].regenerated, false);
    await page.locator("#reset-button").click();
    await submit(page, "新的问题");
    assert.equal(requests[2].new_chat, true);
    assert.equal(requests[2].resource, null);
});

test("重新生成复用原问题并指向原回答", async (t) => {
    let calls = 0;
    const { page, requests } = await pageFor(t, {
        onQuestion: (route, fulfill) => {
            calls++;
            return fulfill(answer("SQL_VALIDATED", {
                conversationId: "conversation-1",
                turnId: `turn-${calls}`,
                mode: calls === 1 ? "NEW" : "REGENERATE"
            }));
        }
    });
    await submit(page, "重新统计");
    await page.locator("#regenerate-button").click();
    await page.waitForFunction(() => document.querySelector(".answer-panel").getAttribute("aria-busy") === "false");
    assert.equal(requests[1].question, "重新统计");
    assert.equal(requests[1].new_chat, false);
    assert.equal(requests[1].resource, "turn-1");
    assert.equal(requests[1].regenerated, true);
});

test("上下文失效后清除追问指针并提示从新对话开始", async (t) => {
    let calls = 0;
    const { page } = await pageFor(t, {
        onQuestion: (route, fulfill) => {
            calls++;
            return calls === 1
                ? fulfill(answer("SQL_VALIDATED", { conversationId: "conversation-1", turnId: "turn-1", mode: "NEW" }))
                : fulfill({ errorCode: "CONTEXT_SCHEMA_CHANGED", message: "授权表结构已变化，请开始新对话。", requestId: "context-error" }, 409);
        }
    });
    await submit(page, "第一个问题");
    await submit(page, "继续追问");
    assert.equal(await page.locator("#status-title").innerText(), "会话已失效");
    assert.equal(await page.locator("#question").getAttribute("aria-label"), "新的问题");
});

for (const [status, title, hasSql] of [["NEEDS_CLARIFICATION", "还需要一点信息", false],
    ["SQL_REJECTED", "查询未完成", false], ["QUERY_FAILED", "查询未完成", true]]) {
    test(`${status} 正确展示提示和错误边界`, async (t) => {
        const { page } = await pageFor(t, { answer: answer(status, { sql: hasSql ? sql : null,
            validation: hasSql ? answer().validation : null, rows: [], columns: [],
            message: "请明确查询范围。", errorCode: status === "NEEDS_CLARIFICATION" ? null : "TEST_ERROR" }) });
        await submit(page);
        assert.equal(await page.locator("#status-title").innerText(), title);
        assert.equal(await visible(page, "#sql-section"), hasSql);
        assert.equal(await visible(page, "#table-section"), false);
        assert.equal(await page.locator("#message").innerText(), "请明确查询范围。");
    });
}

test("成功响应按 columns 顺序渲染，保留零、false、NULL 和文本", async (t) => {
    const { page } = await pageFor(t, { enabled: true, answer: answer("QUERY_SUCCEEDED", {
        columns: ["name", "count", "active", "missing", "empty"],
        rows: [{ count: 0, name: "商铺", active: false, missing: null, empty: "" }] }) });
    await submit(page);
    assert.deepEqual(await page.locator("tbody td").allTextContents(), ["1", "商铺", "0", "false", "NULL", ""]);
    assert.equal(await page.locator("#row-count").innerText(), "1 行");
});

test("空结果保留表头，禁用导出并给出说明", async (t) => {
    const { page } = await pageFor(t, { answer: answer("QUERY_SUCCEEDED", { rows: [] }) });
    await submit(page);
    assert.deepEqual(await page.locator("thead th").allTextContents(), ["#", "type_id", "shop_count"]);
    assert.match(await page.locator("#table-empty").innerText(), /没有符合条件的数据/);
    assert.equal(await page.locator("#export-csv").isDisabled(), true);
});

test("截断结果明确提示，桌面展示完整查询依据", async (t) => {
    const { page } = await pageFor(t, { enabled: true, answer: answer("QUERY_SUCCEEDED", { truncated: true }) });
    await submit(page);
    assert.equal(await visible(page, "#truncation-note"), true);
    assert.deepEqual(await page.locator("tbody td").allTextContents(), ["1", "1", "9", "2", "2", "5"]);
    await page.screenshot({ path: path.join(output, "workbench-results-desktop.png"), fullPage: true });
});

test("50 行分页、筛选重置页码、无匹配提示与清空筛选", async (t) => {
    const rows = Array.from({ length: 105 }, (_, index) => ({ id: index + 1, name: `商铺 ${index + 1}` }));
    const { page } = await pageFor(t, { answer: answer("QUERY_SUCCEEDED", { columns: ["id", "name"], rows }) });
    await submit(page);
    assert.equal(await page.locator("tbody tr").count(), 50);
    await page.locator("#next-page").click();
    assert.equal(await page.locator("tbody td").first().innerText(), "51");
    await page.locator("#next-page").click();
    assert.equal(await page.locator("tbody tr").count(), 5);
    assert.equal(await page.locator("#next-page").isDisabled(), true);
    await page.locator("#previous-page").click();
    assert.equal(await page.locator("tbody td").first().innerText(), "51");
    await page.locator("#result-filter").fill("商铺 105");
    assert.equal(await page.locator("tbody tr").count(), 1);
    assert.match(await page.locator("#filter-count").innerText(), /匹配 1 \/ 105/);
    await page.locator("#result-filter").fill("不存在");
    assert.match(await page.locator("#table-empty").innerText(), /没有匹配/);
    await page.locator("#result-filter").fill("");
    assert.equal(await page.locator("tbody tr").count(), 50);
    assert.equal(await page.locator("#previous-page").isDisabled(), true);
});

test("澄清回答不残留当前表格，上一条结果仍保留在对话中", async (t) => {
    let calls = 0;
    const { page } = await pageFor(t, { onQuestion: (route, fulfill) => fulfill(++calls === 1 ? answer()
        : answer("NEEDS_CLARIFICATION", { sql: null, validation: null, rows: [], columns: [], message: "请说明时间范围。" })) });
    await submit(page);
    await page.locator("#result-filter").fill("1");
    await submit(page, "再问一个问题");
    assert.equal(await visible(page, "#sql-section"), false);
    assert.equal(await visible(page, "#table-section"), false);
    assert.equal(await page.locator("#result-table tbody tr").count(), 0);
    assert.equal(await page.locator("#past-messages tbody tr").count(), 2);
    await page.locator("#reset-button").click();
    assert.equal(await page.locator("#question").inputValue(), "");
    assert.equal(await visible(page, "#empty-state"), true);
    assert.equal(await visible(page, "#status-banner"), false);
});

test("等待状态禁止重复提交，Ctrl+Enter 可提交且正确恢复控件", async (t) => {
    let release;
    const blocked = new Promise((resolve) => { release = resolve; });
    const { page, requests } = await pageFor(t, { onQuestion: async (route, fulfill) => { await blocked; return fulfill(answer()); } });
    await page.locator("#question").fill("统计商铺");
    const started = page.waitForRequest("**/api/chat/question");
    await page.locator("#question").press("Control+Enter");
    await page.waitForFunction(() => document.getElementById("submit-button").disabled);
    assert.equal(await page.locator("#reset-button").isDisabled(), true);
    assert.equal(await page.locator("#question").isDisabled(), true);
    assert.equal(await page.locator(".example-button").first().isDisabled(), true);
    await page.locator("#question-form").evaluate((form) => form.requestSubmit());
    await started;
    assert.equal(requests.length, 1);
    release();
    await page.waitForFunction(() => !document.getElementById("submit-button").disabled);
    assert.equal(await page.locator("#reset-button").isDisabled(), false);
    assert.equal(await page.locator("#question").isDisabled(), false);
});

test("HTTP 502 显示统一错误码和 requestId，随后可成功重试", async (t) => {
    let calls = 0;
    const { page } = await pageFor(t, { onQuestion: (route, fulfill) => ++calls === 1
        ? fulfill({ errorCode: "MODEL_REQUEST_FAILED", message: "模型请求失败，请稍后重试。", requestId: "test-request-id" }, 502)
        : fulfill(answer()) });
    await submit(page);
    assert.match(await page.locator("#error-detail").innerText(), /MODEL_REQUEST_FAILED.*test-request-id/);
    assert.equal(await visible(page, "#sql-section"), false);
    await submit(page);
    assert.equal(await page.locator("#status-title").innerText(), "查询完成");
    assert.equal(await visible(page, "#error-detail"), false);
});

test("网络失败给出提示，表单可重新提交", async (t) => {
    const { page } = await pageFor(t, { onQuestion: (route) => route.abort("failed") });
    await submit(page);
    assert.match(await page.locator("#message").innerText(), /无法连接服务/);
    assert.equal(await page.locator("#submit-button").isDisabled(), false);
});

test("非 JSON 响应不显示 HTML 错误页内容", async (t) => {
    const { page } = await pageFor(t, { onQuestion: (route) => route.fulfill({ status: 502,
        contentType: "text/html", body: "<h1>private-internal-detail</h1>" }) });
    await submit(page);
    assert.match(await page.locator("#message").innerText(), /响应格式不正确/);
    assert.equal((await page.locator("body").innerText()).includes("private-internal-detail"), false);
});

test("未知业务状态不当成成功响应展示", async (t) => {
    const { page } = await pageFor(t, { answer: answer("UNKNOWN") });
    await submit(page);
    assert.match(await page.locator("#message").innerText(), /查询状态不正确/);
    assert.equal(await visible(page, "#table-section"), false);
});

test("结果结构异常给出格式提示，不抛出渲染异常", async (t) => {
    const { page } = await pageFor(t, { answer: answer("QUERY_SUCCEEDED", { rows: [null] }) });
    await submit(page);
    assert.match(await page.locator("#message").innerText(), /数据格式不正确/);
});

test("SQL、列名、单元格、错误消息和元数据均按文本显示", async (t) => {
    const malicious = '<img src=x onerror="window.injected=true">';
    const { page } = await pageFor(t, { schemas: [{ ...schemas[0], tableName: malicious }],
        answer: answer("QUERY_SUCCEEDED", { sql: malicious, columns: [malicious], rows: [{ [malicious]: malicious }],
            message: malicious, validation: { tables: [malicious], functions: [malicious] } }) });
    await submit(page);
    assert.equal(await page.locator("#sql").innerText(), malicious);
    assert.equal(await page.locator("tbody td").nth(1).innerText(), malicious);
    assert.equal(await page.locator("img").count(), 0);
    assert.equal(await page.evaluate(() => window.injected), undefined);
});

test("复制按钮写入原始 SQL，剪贴板受限时给出备用提示", async (t) => {
    const { page } = await pageFor(t);
    await page.evaluate(() => Object.defineProperty(navigator, "clipboard", { configurable: true,
        value: { writeText: async (text) => { window.copiedSql = text; } } }));
    await submit(page);
    await page.locator("#copy-sql").click();
    assert.equal(await page.evaluate(() => window.copiedSql), sql);
    assert.equal(await page.locator("#copy-sql").innerText(), "已复制");
    await page.evaluate(() => Object.defineProperty(navigator, "clipboard", {
        value: { writeText: async () => { throw new Error("denied"); } } }));
    await page.locator("#copy-sql").click();
    assert.match(await page.locator("#copy-sql").innerText(), /复制失败/);
});

test("CSV 导出包含全部返回行、UTF-8 BOM、转义及公式保护", async (t) => {
    const { page } = await pageFor(t, { answer: answer("QUERY_SUCCEEDED", { columns: ["name", "value", "nullable"],
        rows: [{ name: '=HYPERLINK("x")', value: -2, nullable: null },
            { name: '中文,"引号"\n换行', value: "+1+1", nullable: false },
            { name: "@SUM(1)", value: " -1+1", nullable: "" }] }) });
    await submit(page);
    await page.locator("#result-filter").fill("@SUM");
    const downloading = page.waitForEvent("download");
    await page.locator("#export-csv").click();
    const download = await downloading;
    const file = path.join(output, "export-test.csv");
    await download.saveAs(file);
    const bytes = await fs.readFile(file);
    assert.deepEqual([...bytes.subarray(0, 3)], [239, 187, 191]);
    const csv = bytes.toString("utf8");
    assert.match(csv, /"'=HYPERLINK\(""x""\)"/);
    assert.match(csv, /"-2",""/);
    assert.match(csv, /中文,""引号""\n换行/);
    assert.match(csv, /"'\+1\+1"/);
    assert.match(csv, /"'@SUM\(1\)"/);
    assert.match(csv, /"' -1\+1"/);
    assert.match(csv, /"false"/);
});

test("配置读取失败不伪称开启执行，刷新后恢复", async (t) => {
    let calls = 0;
    const { page } = await pageFor(t, { schemaStatus: 503, schemas: { errorCode: "INTERNAL_ERROR" },
        onPolicy: (route, fulfill) => ++calls === 1 ? fulfill({ message: "暂不可用" }, 503) : fulfill(policy) });
    assert.equal(await page.locator("#mode-badge").innerText(), "执行模式未知");
    assert.match(await page.locator("#source-summary").innerText(), /暂时无法读取/);
    await page.locator("#refresh-config").click();
    await page.waitForFunction(() => document.getElementById("mode-badge").textContent.includes("校验模式"));
    assert.equal(await page.locator("#submit-label").innerText(), "生成 SQL");
});

test("390px 窄屏长 SQL 和宽表不撑破页面，操作可见", async (t) => {
    const columns = Array.from({ length: 15 }, (_, i) => `长列名_${i}`);
    const { page } = await pageFor(t, { enabled: true, viewport: { width: 390, height: 844 },
        answer: answer("QUERY_SUCCEEDED", { sql: "SELECT " + columns.join(", ") + " FROM tb_shop",
            columns, rows: [Object.fromEntries(columns.map((c) => [c, "很长的内容".repeat(15)]))] }) });
    await submit(page);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    const statusPosition = await page.locator("#status-banner").boundingBox();
    assert.ok(statusPosition.y >= 0 && statusPosition.y < 844, "窄屏完成后应自动定位到答案");
    assert.equal(await page.locator("#table-scroll").evaluate((element) => element.scrollWidth > element.clientWidth), true);
    await page.screenshot({ path: path.join(output, "workbench-results-mobile.png"), fullPage: true });
});

test("320px 窄屏空状态无横向溢出，键盘焦点与减少动画可用", async (t) => {
    const { page } = await pageFor(t, { viewport: { width: 320, height: 800 } });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    await page.keyboard.press("Tab");
    assert.equal(await page.locator(".skip-link").evaluate((element) => element === document.activeElement), true);
    await page.keyboard.press("Enter");
    assert.equal(await page.locator("#question").evaluate((element) => element === document.activeElement), true);
    assert.equal(await page.evaluate(() => matchMedia("(prefers-reduced-motion: reduce)").matches), true);
    await page.screenshot({ path: path.join(output, "workbench-empty-mobile.png"), fullPage: true });
});

async function credentials(page, username = "test-reader", password = "test-only-password") {
    await page.locator("#username").fill(username);
    await page.locator("#password").fill(password);
}
async function loginAttempt(page) {
    await page.locator("#login-button").click();
    await page.waitForFunction(() => !document.getElementById("login-button")?.disabled);
}

test("登录页沿用现有样式，密码输入与自动填充属性正确", async (t) => {
    const { page, authRequests } = await pageFor(t, { startPath: "/login.html" });
    assert.equal(await page.locator("#password").getAttribute("type"), "password");
    assert.equal(await page.locator("#username").getAttribute("autocomplete"), "username");
    assert.equal(await page.locator("#password").getAttribute("autocomplete"), "current-password");
    assert.equal(authRequests.length, 0);
    await page.screenshot({ path: path.join(output, "login-desktop.png"), fullPage: true });
});

for (const width of [320, 390]) {
    test(`${width}px 登录页无横向溢出，用户名和登录操作可见`, async (t) => {
        const { page } = await pageFor(t, { startPath: "/login.html", viewport: { width, height: 844 } });
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
        assert.equal(await page.locator("#login-button").isVisible(), true);
        await page.screenshot({ path: path.join(output, `login-mobile-${width}.png`), fullPage: true });
    });
}

test("未输入凭据时本地拦截，不请求 CSRF 或登录", async (t) => {
    const { page, authRequests, tokensRead } = await pageFor(t, { startPath: "/login.html" });
    await loginAttempt(page);
    assert.match(await page.locator("#login-message").innerText(), /请输入用户名和密码/);
    assert.equal(await page.locator("#username").evaluate((e) => e === document.activeElement), true);
    await page.locator("#username").fill("reader");
    await loginAttempt(page);
    assert.equal(await page.locator("#password").evaluate((e) => e === document.activeElement), true);
    assert.equal(authRequests.length, 0);
    assert.equal(tokensRead(), 0);
});

test("正确登录使用表单编码、保留密码空格，读取身份并进入工作台", async (t) => {
    const { page, authRequests, tokensRead } = await pageFor(t, { startPath: "/login.html?next=https://example.invalid" });
    await credentials(page, " test-reader ", " p&a=字 ");
    await page.locator("#login-button").click();
    await page.waitForURL(origin + "/");
    await page.waitForFunction(() => !document.getElementById("submit-button").disabled);
    assert.equal(await page.locator("#current-user").innerText(), "test-reader");
    const params = new URLSearchParams(authRequests[0].body);
    assert.equal(params.get("username"), "test-reader");
    assert.equal(params.get("password"), " p&a=字 ");
    assert.equal(authRequests.length, 1);
    assert.equal(tokensRead(), 1);
    assert.deepEqual(await page.evaluate(() => [localStorage.length, sessionStorage.length]), [0, 0]);
});

test("密码错误显示安全消息和请求标识，清空密码后允许再次提交", async (t) => {
    const { page, authRequests } = await pageFor(t, { startPath: "/login.html", onLogin: (route, fulfill) =>
        fulfill({ errorCode: "AUTHENTICATION_FAILED", message: "用户名或密码不正确。", requestId: "login-request" }, 401) });
    await credentials(page, "test-reader", "wrong-private-password");
    await loginAttempt(page);
    assert.match(await page.locator("#login-message").innerText(), /用户名或密码不正确.*login-request/);
    assert.equal(await page.locator("#password").inputValue(), "");
    assert.equal(await page.locator("#username").inputValue(), "test-reader");
    assert.equal((await page.locator("body").innerText()).includes("wrong-private-password"), false);
    assert.equal(authRequests.length, 1);
    assert.equal(new URL(page.url()).pathname, "/login.html");
});

test("CSRF 拒绝不自动重放登录，用户重试时读取新 token", async (t) => {
    let calls = 0;
    const { page, authRequests, tokensRead } = await pageFor(t, { startPath: "/login.html", onLogin: (route, fulfill) =>
        ++calls === 1 ? fulfill({ errorCode: "CSRF_INVALID", message: "安全校验失败，请刷新页面后重试。" }, 403)
            : fulfill({ username: "test-reader" }) });
    await credentials(page);
    await loginAttempt(page);
    assert.match(await page.locator("#login-message").innerText(), /安全校验失败/);
    assert.equal(authRequests.length, 1);
    await credentials(page);
    await page.locator("#login-button").click();
    await page.waitForURL(origin + "/");
    assert.equal(tokensRead(), 2);
    assert.deepEqual(authRequests.map((request) => request.token), [1, 2]);
});

test("登录网络失败清空密码并恢复表单，不自动重试", async (t) => {
    const { page, authRequests } = await pageFor(t, { startPath: "/login.html", onLogin: (route) => route.abort("failed") });
    await credentials(page);
    await loginAttempt(page);
    assert.match(await page.locator("#login-message").innerText(), /无法连接服务/);
    assert.equal(await page.locator("#password").inputValue(), "");
    assert.equal(authRequests.length, 1);
});

for (const failure of ["network", "malformed"]) {
    test(`无法获取 CSRF（${failure}）时不发送凭据`, async (t) => {
        const { page, authRequests } = await pageFor(t, { startPath: "/login.html", onCsrf: (route, fulfill) =>
            failure === "network" ? route.abort("failed") : fulfill({ headerName: "bad header", token: "" }) });
        await credentials(page);
        await loginAttempt(page);
        assert.equal(authRequests.length, 0);
        assert.equal(await page.locator("#password").inputValue(), "");
        assert.equal(await page.locator("#login-message").isVisible(), true);
    });
}

test("登录等待期间阻止重复提交，回车只发送一次", async (t) => {
    let release;
    const blocked = new Promise((resolve) => { release = resolve; });
    const { page, authRequests, tokensRead } = await pageFor(t, { startPath: "/login.html", onLogin: async (route, fulfill) => {
        await blocked; return fulfill({ username: "test-reader" });
    } });
    await credentials(page);
    const started = page.waitForRequest("**/api/auth/login");
    await page.locator("#password").press("Enter");
    await started;
    assert.equal(await page.locator("#username").isDisabled(), true);
    await page.locator("#login-form").evaluate((form) => form.requestSubmit());
    assert.equal(authRequests.length, 1);
    assert.equal(tokensRead(), 1);
    release();
    await page.waitForURL(origin + "/");
});

test("已有会话直接进入工作台，无需再次输入密码", async (t) => {
    const { page, authRequests } = await pageFor(t, { startPath: "/login.html", loggedIn: true });
    await page.waitForURL(origin + "/");
    assert.equal(authRequests.length, 0);
});

test("匿名打开工作台跳转登录，不读取表结构或提交问数", async (t) => {
    let configCalls = 0;
    const { page, requests } = await pageFor(t, { loggedIn: false, onPolicy: (route, fulfill) => {
        ++configCalls; return fulfill(policy);
    } });
    await page.waitForURL(origin + "/login.html");
    assert.equal(configCalls, 0);
    assert.equal(requests.length, 0);
});

test("问数返回 401 清除页面并跳转，不自动重放问题", async (t) => {
    const { page, requests } = await pageFor(t, { onQuestion: (route, fulfill) =>
        fulfill({ errorCode: "UNAUTHENTICATED", message: "请先登录。" }, 401) });
    await page.locator("#question").fill("统计商铺");
    await page.locator("#submit-button").click();
    await page.waitForURL(origin + "/login.html?reason=expired");
    assert.match(await page.locator("#login-notice").innerText(), /登录状态已失效/);
    assert.equal(requests.length, 1);
    assert.equal(await page.locator("#sql").count(), 0);
});

test("退出携带新 CSRF，清除工作台结果并显示退出提示", async (t) => {
    const { page, authRequests, tokensRead } = await pageFor(t);
    await submit(page);
    await page.locator("#logout-button").click();
    await page.waitForURL(origin + "/login.html?reason=loggedout");
    assert.match(await page.locator("#login-notice").innerText(), /已退出登录/);
    assert.deepEqual(authRequests.map((request) => request.path), ["/api/auth/logout"]);
    assert.equal(authRequests[0].token, 2);
    assert.equal(tokensRead(), 2);
});

test("退出被拒绝时保留会话并恢复操作，重试使用新 token", async (t) => {
    let calls = 0;
    const { page, authRequests } = await pageFor(t, { onLogout: (route, fulfill) => ++calls === 1
        ? fulfill({ errorCode: "CSRF_INVALID", message: "安全校验失败。", requestId: "logout-request" }, 403)
        : fulfill({ message: "已退出登录。" }) });
    await page.locator("#logout-button").click();
    await page.waitForFunction(() => !document.getElementById("logout-button").disabled);
    assert.equal(new URL(page.url()).pathname, "/");
    assert.equal(await page.locator("#status-title").innerText(), "退出未完成");
    assert.match(await page.locator("#error-detail").innerText(), /CSRF_INVALID.*logout-request/);
    assert.equal(authRequests.length, 1);
    await page.locator("#logout-button").click();
    await page.waitForURL(origin + "/login.html?reason=loggedout");
    assert.deepEqual(authRequests.map((r) => r.token), [1, 2]);
});

test("恢复历史页面时重新确认会话，清除旧结果", async (t) => {
    let calls = 0;
    const { page } = await pageFor(t, { onMe: (route, fulfill) => ++calls === 1
        ? fulfill({ username: "test-reader" }) : fulfill({ errorCode: "UNAUTHENTICATED", message: "请先登录。" }, 401) });
    await submit(page);
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true })));
    await page.waitForURL(origin + "/login.html");
    assert.equal(await page.locator("#sql").count(), 0);
});

test("身份确认失败时禁用问题输入，恢复服务后可刷新", async (t) => {
    let calls = 0;
    const { page } = await pageFor(t, { onMe: (route, fulfill) => ++calls === 1
        ? fulfill({ message: "服务暂不可用。" }, 503) : fulfill({ username: "test-reader" }) });
    assert.equal(await page.locator("#question").isDisabled(), true);
    assert.equal(await page.locator("#submit-button").isDisabled(), true);
    await page.locator("#refresh-config").click();
    await page.waitForFunction(() => !document.getElementById("submit-button").disabled);
    await submit(page);
    assert.equal(await page.locator("#status-title").innerText(), "查询完成");
});

test("用户名和登录错误按文本显示，外部跳转参数不起作用", async (t) => {
    const malicious = '<img src=x onerror="window.injected=true">';
    const { page } = await pageFor(t, { username: malicious });
    assert.equal(await page.locator("#current-user").innerText(), malicious);
    assert.equal(await page.locator("img").count(), 0);
    assert.equal(await page.evaluate(() => window.injected), undefined);
    const { page: login } = await pageFor(t, { startPath: "/login.html?reason=" + encodeURIComponent(malicious),
        onLogin: (route, fulfill) => fulfill({ message: malicious }, 401) });
    assert.equal(await login.locator("#login-notice").isVisible(), false);
    await credentials(login);
    await loginAttempt(login);
    assert.equal(await login.locator("#login-message").innerText(), malicious);
    assert.equal(await login.locator("img").count(), 0);
});


test("仅显示当前账号的表结构", async (t) => {
    const { page } = await pageFor(t, { schemas: [schemas[1]], username: "second-reader" });
    assert.equal(await page.locator(".source-table").count(), 1);
    assert.equal(await page.locator(".source-table summary").innerText(), "tb_shop_type");
    assert.match(await page.locator("#source-summary").innerText(), /1 张授权表/);
    assert.equal(await page.locator("#current-user").innerText(), "second-reader");
});

const noTableAccess = { errorCode: "TABLE_ACCESS_DENIED", message: "当前账号没有可用的数据表授权，请联系管理员配置。", requestId: "test-table-access" };

test("没有表授权时显示原因且阻止按钮和快捷键调用模型", async (t) => {
    const { page, requests } = await pageFor(t, { schemas: noTableAccess, schemaStatus: 403 });
    assert.equal(await page.locator("#submit-button").isDisabled(), true);
    assert.equal(await page.locator(".example-button").first().isDisabled(), true);
    assert.equal(await page.locator("#logout-button").isEnabled(), true);
    assert.equal(await page.locator(".source-table").count(), 0);
    assert.match(await page.locator("#status-title").innerText(), /需要数据表授权/);
    assert.match(await page.locator("#error-detail").innerText(), /TABLE_ACCESS_DENIED/);
    await page.locator("#question").fill("统计商铺");
    await page.locator("#question").press("Control+Enter");
    await page.evaluate(() => document.getElementById("question-form").requestSubmit());
    assert.equal(requests.length, 0);
    assert.equal(new URL(page.url()).pathname, "/");
});

test("空表列表同样阻止问数请求", async (t) => {
    const { page, requests } = await pageFor(t, { schemas: [] });
    assert.equal(await page.locator("#submit-button").isDisabled(), true);
    assert.match(await page.locator("#source-summary").innerText(), /没有可查询/);
    assert.equal(requests.length, 0);
});

test("授权补齐后刷新配置恢复提交", async (t) => {
    let granted = false;
    const { page, requests } = await pageFor(t, { onSchema: (route, fulfill) =>
        granted ? fulfill([schemas[1]]) : fulfill(noTableAccess, 403) });
    assert.equal(await page.locator("#submit-button").isDisabled(), true);
    granted = true;
    await page.locator("#refresh-config").click();
    await page.waitForFunction(() => !document.getElementById("submit-button").disabled);
    assert.equal(await page.locator(".source-table").count(), 1);
    assert.equal(await visible(page, "#status-banner"), false);
    await submit(page);
    assert.equal(requests.length, 1);
});

test("问数过程中收到授权拒绝会清理旧结果并停止后续提交", async (t) => {
    let revoked = false;
    const { page, requests } = await pageFor(t, { onQuestion: (route, fulfill) =>
        revoked ? fulfill(noTableAccess, 403) : fulfill(answer()) });
    await submit(page);
    assert.equal(await visible(page, "#table-section"), true);
    revoked = true;
    await submit(page);
    assert.equal(await visible(page, "#table-section"), false);
    assert.equal(await page.locator(".source-table").count(), 0);
    assert.equal(await page.locator("#submit-button").isDisabled(), true);
    assert.equal(await page.locator("#logout-button").isEnabled(), true);
    assert.equal(requests.length, 2);
});
