/* 真实 Edge/Chromium 页面测试：读取源码静态文件，拦截所有 API；不触及模型或数据库。 */
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

before(async () => {
    await fs.mkdir(output, { recursive: true });
    server = http.createServer(async (request, response) => {
        const pathname = new URL(request.url, "http://localhost").pathname;
        const assets = { "/": "index.html", "/index.html": "index.html", "/styles.css": "styles.css", "/app.js": "app.js",
            "/auth.js": "auth.js", "/login.html": "login.html", "/login.js": "login.js" };
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
const visible = (page, selector) => page.locator(selector).isVisible();

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

test("执行模式来自策略接口，界面明确显示查询和返回上限", async (t) => {
    const { page } = await pageFor(t, { enabled: true });
    assert.equal(await page.locator("#submit-label").innerText(), "查询数据");
    assert.match(await page.locator("#mode-badge").innerText(), /已开启执行/);
    assert.match(await page.locator("#query-limits").innerText(), /5 秒.*1000 行/);
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
    assert.deepEqual(requests, [{ question: "统计商铺" }]);
    assert.equal(await page.locator("#sql").innerText(), sql);
    assert.match(await page.locator("#validation-tables").innerText(), /mybatis.tb_shop/);
    assert.equal(await page.locator("#validation-functions").innerText(), "COUNT");
    assert.equal(await visible(page, "#table-section"), false);
    assert.match(await page.locator("#status-title").innerText(), /尚未执行/);
    await page.screenshot({ path: path.join(output, "workbench-validated-desktop.png"), fullPage: true });
});

for (const [status, title, hasSql] of [["NEEDS_CLARIFICATION", "还需要一点信息", false],
    ["SQL_REJECTED", "SQL 未通过规则检查", false], ["QUERY_FAILED", "查询未完成", true]]) {
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

test("新问题清除旧 SQL、筛选和表格，澄清不会残留上次数据", async (t) => {
    let calls = 0;
    const { page } = await pageFor(t, { onQuestion: (route, fulfill) => fulfill(++calls === 1 ? answer()
        : answer("NEEDS_CLARIFICATION", { sql: null, validation: null, rows: [], columns: [], message: "请说明时间范围。" })) });
    await submit(page);
    await page.locator("#result-filter").fill("1");
    await submit(page, "再问一个问题");
    assert.equal(await visible(page, "#sql-section"), false);
    assert.equal(await visible(page, "#table-section"), false);
    assert.equal(await page.locator("tbody tr").count(), 0);
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
