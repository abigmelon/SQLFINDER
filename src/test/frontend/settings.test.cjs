/* 设置页面测试 */
const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const fs = require("node:fs/promises");
const path = require("node:path");
const { chromium } = require("playwright");
const root = path.resolve(__dirname, "../../main/resources/static");
const output = path.resolve(__dirname, "../../../output/playwright");
const config = { provider: "DeepSeek", baseUrl: "https://api.deepseek.com", model: "deepseek-chat",
    apiKeyConfigured: true, timeoutSeconds: 60, source: "startup" };
const dbConnection = {host:"127.0.0.1",port:3306,catalog:"mybatis",username:"sqlfinder_reader",passwordConfigured:true,
    requireTls:false,tables:["tb_shop","tb_shop_type"]};
const dbView = {catalog:"mybatis",tables:["tb_shop","tb_shop_type"],accountTables:["mybatis.tb_shop"],
    connection:{active:dbConnection,saved:null,pendingRestart:false}};
let browser, server, origin;
before(async () => {
    await fs.mkdir(output, { recursive: true });
    server = http.createServer(async (req, res) => {
        const file = new URL(req.url, "http://localhost").pathname.slice(1);
        if (!["settings.html", "settings.css", "settings.js", "auth.js", "login.html", "login.js", "styles.css"].includes(file)) { res.writeHead(404).end(); return; }
        res.writeHead(200, { "Content-Type": (file.endsWith("html") ? "text/html" : file.endsWith("css") ? "text/css" : "text/javascript") + "; charset=utf-8" });
        res.end(await fs.readFile(path.join(root, file)));
    });
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    origin = `http://127.0.0.1:${server.address().port}`;
    browser = await chromium.launch({ headless: true, channel: process.env.SQLFINDER_BROWSER_CHANNEL || (process.platform === "win32" ? "msedge" : undefined) });
});
after(async () => {
    if (browser) await browser.close();
    if (server) await new Promise(resolve => server.close(resolve));
});
async function pageFor(t, options = {}) {
    const context = await browser.newContext({ viewport: options.viewport || { width: 1440, height: 1000 } });
    const page = await context.newPage(), requests = [], errors = [];
    let token = 0, reads = 0;
    page.on("pageerror", e => errors.push(e.message));
    await context.route("**/api/**", async route => {
        const pathname = new URL(route.request().url()).pathname;
        const fulfill = (body, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
        if (pathname === "/api/settings/access") return fulfill({ canManageSettings: options.admin !== false });
        if (pathname === "/api/system/diagnostics") {
            assert.equal(route.request().method(), "GET");
            if (options.onDiagnostics) return options.onDiagnostics(route, fulfill);
            return fulfill({ status: "READY", model: { status: "READY", message: "模型密钥已配置。" },
                database: { status: "READY", privileges: "READ_ONLY", tables: [], message: "授权表可读取，当前数据库账号已确认只读。" },
                history: { status: "READY", message: "本地会话记录可读取。" } });
        }
        if (pathname === "/api/settings/database" && route.request().method() === "GET") return fulfill(options.databaseView || dbView);
        if (pathname === "/api/auth/me") return fulfill({ errorCode: "UNAUTHENTICATED", message: "请登录" }, 401);
        if (pathname === "/api/auth/csrf") {
            token++;
            if (options.csrfFailure) return fulfill({ message: "安全校验失败", errorCode: "CSRF_INVALID" }, 403);
            return fulfill({ headerName: "X-CSRF-TOKEN", token: `csrf-${token}` });
        }
        if (pathname === "/api/settings/database" || pathname.startsWith("/api/settings/database/")) {
            assert.equal(route.request().method(),"POST");
            assert.equal(route.request().headers()["x-csrf-token"],`csrf-${token}`);
            requests.push({path:pathname,body:route.request().postData() ? route.request().postDataJSON() : null});
            if (options.onDatabase) return options.onDatabase(route,fulfill);
            const body=requests.at(-1).body;
            if (pathname === "/api/settings/database") return fulfill({active:dbConnection,saved:{...dbConnection,...body,
                password:undefined,clearPassword:undefined,passwordConfigured:!body.clearPassword},pendingRestart:true});
            if (pathname.endsWith("/test")) return fulfill({account:"sqlfinder_reader@localhost",catalog:body.catalog,privileges:"READ_ONLY",
                tables:["tb_shop","tb_shop_type"],truncated:false,previews:body.tables.map(name=>({name,columns:[{name:"id",type:"BIGINT",nullable:false},{name:"name",type:"VARCHAR",nullable:true}]}))});
            return pathname.endsWith("/check") ? fulfill({account:"sqlfinder_reader@localhost",catalog:"mybatis",privileges:"READ_ONLY",durationMs:10,
                tables:[{table:"tb_shop",databaseReadable:true,applicationAllowed:true},{table:"tb_shop_type",databaseReadable:true,applicationAllowed:false}]})
                : fulfill({sql:"CREATE USER 'sqlfinder_reader'@'localhost' IDENTIFIED BY 'REPLACE_WITH_A_RANDOM_STRONG_PASSWORD';\nGRANT SELECT ON `mybatis`.`tb_shop` TO 'sqlfinder_reader'@'localhost';"});
        }
        if (pathname === "/api/settings/model" && route.request().method() === "GET") {
            reads++;
            return options.onRead ? options.onRead(route, fulfill) : fulfill(config);
        }
        if (["/api/settings/model", "/api/settings/model/test"].includes(pathname)) {
            assert.equal(route.request().method(), "POST");
            assert.equal(route.request().headers()["x-csrf-token"], `csrf-${token}`);
            requests.push({ path: pathname, body: route.request().postDataJSON() });
            if (options.onWrite) return options.onWrite(route, fulfill);
            return pathname.endsWith("/test") ? fulfill({ message: "连接成功，未保存配置。", models: ["deepseek-chat", "deepseek-reasoner"] })
                : fulfill({ ...config, source: "saved", timeoutSeconds: requests.at(-1).body.timeoutSeconds,
                    apiKeyConfigured: !requests.at(-1).body.clearApiKey });
        }
        return route.abort();
    });
    t.after(async () => { await context.close(); assert.deepEqual(errors, []); });
    await page.goto(origin + "/settings.html");
    await page.waitForFunction(() => document.getElementById("feedback").textContent !== "正在读取配置…");
    if (options.admin !== false && !options.onRead) await page.waitForFunction(()=>!document.getElementById("database-controls").hidden);
    return { page, requests, reads: () => reads };
}
async function write(page, selector = "#save-model") {
    await page.locator(selector).click();
    await page.waitForFunction(() => document.getElementById("model-form").getAttribute("aria-busy") !== "true");
}
test("读取设置不回显密钥，显示启动配置", async t => {
    const { page } = await pageFor(t);
    assert.equal(await page.locator("#api-key").inputValue(), "");
    assert.equal(await page.locator("#model-name").inputValue(), "deepseek-chat");
    assert.match(await page.locator("#save-note").innerText(), /启动配置/);
    assert.equal(await page.locator(".back").getAttribute("href"), "/");
    await page.screenshot({ path: path.join(output, "settings-desktop.png"), fullPage: true });
});

test("检查配置显示三个脱敏状态，不调用模型或保存配置", async t => {
    const { page, requests } = await pageFor(t, { viewport: { width: 960, height: 920 } });
    assert.equal(await page.locator("#startup-diagnostics").isVisible(), false);
    await page.locator("#check-configuration").click();
    await page.waitForFunction(() => document.querySelectorAll(".diagnostic-row").length === 3);
    assert.match(await page.locator("#startup-diagnostics").innerText(), /模型[\s\S]*数据库[\s\S]*本地历史/);
    assert.equal(requests.length, 0);
    assert.equal(await page.locator("#api-key").inputValue(), "");
    await page.screenshot({ path: path.join(output, "ui-settings-diagnostics-960.png"), fullPage: true });
});

test("配置诊断失败原因和标记按文本显示", async t => {
    const malicious = '<img src=x onerror="window.injected=true"> 检查数据库服务';
    const { page } = await pageFor(t, { onDiagnostics: (r, f) => f({ status: "NEEDS_ATTENTION",
        model: { status: "NOT_CONFIGURED", message: "请填写密钥。" }, database: { status: "UNAVAILABLE", message: malicious },
        history: { status: "READY", message: "本地历史可读取。" } }) });
    await page.locator("#check-configuration").click();
    await page.waitForFunction(() => document.querySelectorAll(".diagnostic-row").length === 3);
    assert.match(await page.locator("#startup-diagnostics").innerText(), /检查数据库服务/);
    assert.equal(await page.locator("#startup-diagnostics img").count(), 0);
    assert.equal(await page.evaluate(() => window.injected), undefined);
});

test("配置诊断网络失败可重试且按钮不会重复发送", async t => {
    let calls = 0;
    const { page } = await pageFor(t, { onDiagnostics: r => { calls++; return r.abort(); } });
    await page.locator("#check-configuration").click();
    await page.waitForFunction(() => !document.getElementById("check-configuration").disabled);
    assert.match(await page.locator("#startup-diagnostics").innerText(), /无法连接服务/);
    await page.locator("#check-configuration").click();
    await page.waitForFunction(() => !document.getElementById("check-configuration").disabled);
    assert.equal(calls, 2);
});

test("配置诊断会话失效返回登录页", async t => {
    const { page } = await pageFor(t, { onDiagnostics: (r, f) => f({ errorCode: "UNAUTHENTICATED", message: "请先登录。" }, 401) });
    await page.locator("#check-configuration").click();
    await page.waitForURL("**/login.html?reason=expired");
    assert.equal(await page.locator("#login-button").isVisible(), true);
});
test("普通用户不能加载或修改设置", async t => {
    const { page, reads } = await pageFor(t, { admin: false });
    assert.equal(await page.locator("#model-form").isVisible(), false);
    assert.match(await page.locator("#feedback").innerText(), /没有修改设置/);
    assert.equal(reads(), 0);
});
test("测试后保留输入供保存，不把密钥写入浏览器存储", async t => {
    const { page, requests } = await pageFor(t);
    await page.locator("#api-key").fill("private-browser-key");
    await write(page, "#test-model");
    assert.equal(requests[0].path, "/api/settings/model/test");
    assert.equal(requests[0].body.apiKey, "private-browser-key");
    assert.equal(await page.locator("#api-key").inputValue(), "private-browser-key");
    assert.match(await page.locator("#feedback").innerText(), /未保存/);
    assert.equal(await page.locator("#model-options option").count(), 2);
    await write(page);
    assert.equal(requests[1].path, "/api/settings/model");
    assert.equal(requests[1].body.apiKey, "private-browser-key");
    assert.equal(await page.locator("#api-key").inputValue(), "");
    assert.equal(await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]).includes("private-browser-key")), false);
});
test("留空保留密钥，明确勾选后清除", async t => {
    const { page, requests } = await pageFor(t);
    await write(page);
    assert.equal(requests[0].body.apiKey, ""); assert.equal(requests[0].body.clearApiKey, false);
    await page.locator("#api-key").fill("unsaved-key");
    await page.locator("#clear-key").check();
    assert.equal(await page.locator("#api-key").inputValue(), "");
    assert.equal(await page.locator("#api-key").isDisabled(), true);
    await write(page);
    assert.equal(requests[1].body.clearApiKey, true);
    assert.equal(await page.locator("#api-key").isDisabled(), false);
    assert.match(await page.locator("#key-state").innerText(), /未配置/);
});
test("等待期间不能重复提交或修改字段", async t => {
    let release;
    const pending = new Promise(resolve => release = resolve);
    const { page, requests } = await pageFor(t, { onWrite: async (route, fulfill) => { await pending; return fulfill({ ...config, source: "saved" }); } });
    await page.locator("#save-model").click();
    await page.waitForFunction(() => document.getElementById("test-model").disabled);
    assert.equal(await page.locator("#model-name").isDisabled(), true);
    await page.locator("#model-form").dispatchEvent("submit");
    release();
    await page.waitForFunction(() => !document.getElementById("save-model").disabled);
    assert.equal(requests.length, 1);
});
test("保存失败保留输入，错误作为文本展示", async t => {
    const { page } = await pageFor(t, { onWrite: (r, fulfill) => fulfill({ errorCode: "SETTINGS_SAVE_FAILED", message: "<img src=x onerror=alert(1)> 保存失败" }, 503) });
    await page.locator("#api-key").fill("unsaved-key");
    await write(page);
    assert.equal(await page.locator("#api-key").inputValue(), "unsaved-key");
    assert.match(await page.locator("#feedback").innerText(), /保存失败/);
    assert.equal(await page.locator("#feedback img").count(), 0);
});
test("CSRF 获取失败不提交也不重试", async t => {
    const { page, requests } = await pageFor(t, { csrfFailure: true });
    await write(page);
    assert.equal(requests.length, 0);
    assert.match(await page.locator("#feedback").innerText(), /安全校验失败/);
});
test("写请求发现权限失效隐藏表单", async t => {
    const { page } = await pageFor(t, { onWrite: (r, fulfill) => fulfill({ errorCode: "ACCESS_DENIED", message: "无权限" }, 403) });
    await write(page);
    assert.equal(await page.locator("#model-form").isVisible(), false);
});
test("会话失效跳转登录", async t => {
    const { page } = await pageFor(t, { onWrite: (r, fulfill) => fulfill({ errorCode: "UNAUTHENTICATED", message: "请先登录" }, 401) });
    await page.locator("#save-model").click();
    await page.waitForURL("**/login.html?reason=expired");
    assert.equal(await page.locator("#login-button").isVisible(), true);
});
test("读取格式异常不开放保存", async t => {
    const { page } = await pageFor(t, { onRead: (r, fulfill) => fulfill({ model: "deepseek-chat" }) });
    assert.equal(await page.locator("#model-form").isVisible(), false);
    assert.match(await page.locator("#feedback").innerText(), /格式不正确/);
});
test("网络失败后可重试，服务端请求不自动重试", async t => {
    const { page, requests } = await pageFor(t, { onWrite: r => r.abort() });
    await write(page);
    assert.equal(requests.length, 1);
    assert.match(await page.locator("#feedback").innerText(), /无法连接/);
    assert.equal(await page.locator("#save-model").isDisabled(), false);
});
test("无效模型及超时由表单拦截", async t => {
    const { page, requests } = await pageFor(t);
    await page.locator("#model-name").fill("other-model"); await write(page);
    await page.locator("#model-name").fill("deepseek-chat");
    await page.locator("#timeout").fill("91"); await write(page);
    assert.equal(requests.length, 0);
});
test("缓存恢复清除未保存密钥并重新核对权限", async t => {
    const { page, reads } = await pageFor(t);
    await page.locator("#api-key").fill("unsaved-key");
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true })));
    await page.waitForFunction(() => !document.getElementById("model-form").hidden);
    assert.equal(await page.locator("#api-key").inputValue(), ""); assert.equal(reads(), 2);
});
for (const width of [320, 390]) test(`设置页 ${width}px 无横向溢出且可保存`, async t => {
    const { page, requests } = await pageFor(t, { viewport: { width, height: 844 } });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await write(page);
    assert.equal(requests.length, 1);
    await page.screenshot({ path: path.join(output, `settings-mobile-${width}.png`), fullPage: true });
});

test("数据库检查分别显示读取权限和当前应用账号授权",async t=>{
    const {page,requests}=await pageFor(t);
    await page.locator("#check-database").click();
    await page.waitForFunction(()=>!document.getElementById("database-results").hidden);
    assert.match(await page.locator("#database-account").innerText(),/sqlfinder_reader@localhost/);
    assert.match(await page.locator("#database-tables li").nth(1).innerText(),/可读取.*未授权/);
    assert.equal(requests[0].path,"/api/settings/database/check");
    await page.screenshot({path:path.join(output,"settings-database-diagnostics.png"),fullPage:true});
});
test("选择业务表生成脚本，不执行脚本或发送数据库管理员密码",async t=>{
    const {page,requests}=await pageFor(t);
    await page.locator("#grant-tables input").nth(1).uncheck();
    await page.locator("#generate-grants").click();
    await page.waitForFunction(()=>!document.getElementById("grant-output").hidden);
    assert.deepEqual(requests[0].body,{catalog:"mybatis",username:"sqlfinder_reader",host:"localhost",tables:["tb_shop"]});
    assert.match(await page.locator("#grant-sql").innerText(),/REPLACE_WITH_A_RANDOM_STRONG_PASSWORD/);
    assert.match(await page.locator("#database-feedback").innerText(),/数据库管理工具/);
});
test("不选择表时拒绝生成，修改后重新生成不保留旧脚本",async t=>{
    const {page,requests}=await pageFor(t,{onDatabase:(r,f)=>f({errorCode:"INVALID_GRANT_REQUEST",message:"参数不正确"},400)});
    for(const input of await page.locator("#grant-tables input").all()) await input.uncheck();
    await page.locator("#generate-grants").click();
    assert.equal(requests.length,0);
    await page.locator("#grant-tables input").first().check();
    await page.locator("#generate-grants").click();
    await page.waitForFunction(()=>!document.getElementById("generate-grants").disabled);
    assert.equal(await page.locator("#grant-output").isVisible(),false);
});
test("数据库额外权限或角色未知时给出明确提示",async t=>{
    const {page}=await pageFor(t,{onDatabase:(r,f)=>f({account:"root@localhost",catalog:"mybatis",privileges:"EXTRA_PRIVILEGES",tables:[],durationMs:1})});
    await page.locator("#check-database").click();
    await page.waitForFunction(()=>!document.getElementById("database-results").hidden);
    assert.match(await page.locator("#database-privileges").innerText(),/额外权限/);
});
test("数据库错误作为文本展示并恢复操作",async t=>{
    const {page}=await pageFor(t,{onDatabase:(r,f)=>f({errorCode:"DATASOURCE_CONNECTION_FAILED",message:"<img src=x onerror=alert(1)> 连接失败"},503)});
    await page.locator("#check-database").click();
    await page.waitForFunction(()=>!document.getElementById("check-database").disabled);
    assert.match(await page.locator("#database-feedback").innerText(),/连接失败/);
    assert.equal(await page.locator("#database-feedback img").count(),0);
});

async function databaseAction(page,selector="#test-database") {
    await page.locator(selector).click();
    await page.waitForFunction(()=>document.getElementById("database-controls").getAttribute("aria-busy")!=="true");
}
test("连接表单显示活动目标而不回显数据库密码",async t=>{
    const {page}=await pageFor(t);
    assert.equal(await page.locator("#db-host").inputValue(),"127.0.0.1");
    assert.equal(await page.locator("#db-password").inputValue(),"");
    assert.match(await page.locator("#db-password-state").innerText(),/已配置/);
    assert.equal(await page.locator("#candidate-results").isVisible(),false);
});
test("测试候选连接发现表，不保存也不改变活动库",async t=>{
    const {page,requests}=await pageFor(t);
    await page.locator("#db-catalog").fill("new_db"); await page.locator("#db-password").fill("private-db-secret");
    await databaseAction(page);
    assert.equal(requests.length,1); assert.equal(requests[0].path,"/api/settings/database/test"); assert.deepEqual(requests[0].body.tables,[]);
    assert.equal(await page.locator("#connection-tables input").count(),2);
    assert.equal(await page.locator("#database-catalog").innerText(),"mybatis");
    assert.match(await page.locator("#candidate-account").innerText(),/new_db/);
    assert.equal(await page.locator("#db-password").inputValue(),"private-db-secret");
    assert.equal(await page.evaluate(()=>JSON.stringify([Object.entries(localStorage),Object.entries(sessionStorage)]).includes("private-db-secret")),false);
});
test("预览只提交选中的表，按字段类型和空值属性展示",async t=>{
    const {page,requests}=await pageFor(t); await databaseAction(page);
    await page.locator("#connection-tables input").nth(1).uncheck();
    await databaseAction(page,"#preview-database");
    assert.deepEqual(requests.at(-1).body.tables,["tb_shop"]);
    assert.match(await page.locator("#schema-preview").innerText(),/BIGINT.*否/s);
    assert.match(await page.locator("#connection-tables").innerText(),/未授权/);
});
test("保存显示待重启，保留活动库并清除密码输入",async t=>{
    const {page,requests}=await pageFor(t);
    await page.locator("#db-catalog").fill("new_db"); await page.locator("#db-password").fill("private-db-secret");
    await databaseAction(page); await page.locator("#connection-tables input").first().check();
    await databaseAction(page,"#save-database");
    assert.equal(requests.at(-1).path,"/api/settings/database"); assert.deepEqual(requests.at(-1).body.tables,["tb_shop"]);
    assert.equal(requests.at(-1).body.password,"private-db-secret");
    assert.equal(await page.locator("#db-password").inputValue(),"");
    assert.equal(await page.locator("#database-catalog").innerText(),"mybatis");
    assert.match(await page.locator("#connection-state").innerText(),/待重启/);
    assert.match(await page.locator("#connection-note").innerText(),/new_db.*尚未用于问数/);
});
test("读取已保存候选时明确区别活动库和待启用库",async t=>{
    const {page}=await pageFor(t,{databaseView:{...dbView,connection:{active:dbConnection,saved:{...dbConnection,catalog:"pending_db"},pendingRestart:true}}});
    assert.equal(await page.locator("#db-catalog").inputValue(),"pending_db");
    assert.equal(await page.locator("#database-catalog").innerText(),"mybatis");
    assert.match(await page.locator("#connection-state").innerText(),/待重启/);
});
test("修改连接使之前测试与结构预览失效",async t=>{
    const {page,requests}=await pageFor(t); await databaseAction(page); await databaseAction(page,"#preview-database");
    assert.equal(await page.locator("#schema-preview").isVisible(),true);
    await page.locator("#db-host").fill("another.example");
    assert.equal(await page.locator("#candidate-results").isVisible(),false); assert.equal(await page.locator("#schema-preview").isVisible(),false);
    await page.locator("#connection-form").dispatchEvent("submit");
    assert.equal(requests.length,2); assert.match(await page.locator("#database-feedback").innerText(),/先测试/);
});
test("测试失败清空旧候选结果，不能保存陈旧选表",async t=>{
    let calls=0;
    const {page,requests}=await pageFor(t,{onDatabase:(r,f)=>++calls===1 ? f({account:"reader",catalog:"mybatis",privileges:"READ_ONLY",tables:["tb_shop"],truncated:false,previews:[]})
        : f({errorCode:"DATABASE_CANDIDATE_FAILED",message:"连接失败"},503)});
    await databaseAction(page); await databaseAction(page);
    assert.equal(await page.locator("#candidate-results").isVisible(),false);
    await page.locator("#connection-form").dispatchEvent("submit"); assert.equal(requests.length,2);
});
test("保存失败保留表单和原活动配置，错误按文本展示",async t=>{
    const {page}=await pageFor(t,{onDatabase:(r,f)=>r.request().url().endsWith("/test")
        ? f({account:"reader",catalog:"mybatis",privileges:"READ_ONLY",tables:["tb_shop"],truncated:false,previews:[]})
        : f({errorCode:"DATABASE_SAVE_FAILED",message:"<img src=x onerror=alert(1)> 未保存"},503)});
    await page.locator("#db-password").fill("private-db-secret"); await databaseAction(page); await databaseAction(page,"#save-database");
    assert.equal(await page.locator("#db-password").inputValue(),"private-db-secret");
    assert.equal(await page.locator("#database-catalog").innerText(),"mybatis");
    assert.equal(await page.locator("#database-feedback img").count(),0);
    assert.match(await page.locator("#database-feedback").innerText(),/未保存/);
});
test("空表范围不能提交保存，账号空密码需要明确选择",async t=>{
    const {page,requests}=await pageFor(t); await page.locator("#db-clear-password").check();
    assert.equal(await page.locator("#db-password").isDisabled(),true);
    await databaseAction(page); assert.equal(requests[0].body.clearPassword,true);
    for (const input of await page.locator("#connection-tables input").all()) await input.uncheck();
    await databaseAction(page,"#save-database"); assert.equal(requests.length,1);
});
test("数据库请求等待期间禁用表单并阻止重复提交",async t=>{
    let release; const pending=new Promise(r=>release=r);
    const {page,requests}=await pageFor(t,{onDatabase:async(r,f)=>{await pending;return f({account:"reader",catalog:"mybatis",privileges:"READ_ONLY",tables:["tb_shop"],truncated:false,previews:[]});}});
    await page.locator("#test-database").click(); await page.waitForFunction(()=>document.getElementById("db-host").disabled);
    await page.locator("#connection-form").dispatchEvent("submit"); release();
    await page.waitForFunction(()=>!document.getElementById("test-database").disabled);
    assert.equal(requests.length,1);
});
test("字段结构中的标记作为文本展示，320px 不横向溢出",async t=>{
    const {page}=await pageFor(t,{viewport:{width:320,height:844},onDatabase:(r,f)=>f({account:"reader",catalog:"mybatis",privileges:"READ_ONLY",tables:["tb_shop"],truncated:true,
        previews:[{name:"tb_shop",columns:[{name:"<img src=x onerror=alert(1)>",type:"VARCHAR",nullable:true}]}]})});
    await databaseAction(page); await databaseAction(page,"#preview-database");
    assert.equal(await page.locator("#schema-preview img").count(),0);
    assert.match(await page.locator("#candidate-limit").innerText(),/截断/);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.screenshot({path:path.join(output,"settings-connection-mobile-320.png"),fullPage:true});
});
test("数据库权限失效时清除密码并隐藏所有设置",async t=>{
    const {page}=await pageFor(t,{onDatabase:(r,f)=>f({errorCode:"ACCESS_DENIED",message:"无权限"},403)});
    await page.locator("#db-password").fill("private-db-secret"); await page.locator("#api-key").fill("private-key");
    await page.locator("#test-database").click(); await page.waitForFunction(()=>document.getElementById("database-controls").hidden);
    assert.equal(await page.locator("#db-password").inputValue(),""); assert.equal(await page.locator("#api-key").inputValue(),"");
    assert.equal(await page.locator("#model-form").isVisible(),false);
});
test("页面缓存恢复清除密码并丢弃旧候选请求的晚到响应",async t=>{
    let release; const pending=new Promise(r=>release=r);
    const {page,requests}=await pageFor(t,{onDatabase:async(r,f)=>{await pending;return f({account:"stale-account",catalog:"mybatis",privileges:"READ_ONLY",tables:["tb_shop"],truncated:false,previews:[]});}});
    await page.locator("#db-password").fill("private-db-secret"); await page.locator("#test-database").click();
    await page.waitForFunction(()=>document.getElementById("db-host").disabled);
    await page.evaluate(()=>window.dispatchEvent(new PageTransitionEvent("pageshow",{persisted:true})));
    await page.waitForFunction(()=>!document.getElementById("database-controls").hidden);
    release(); await page.waitForFunction(()=>!document.getElementById("test-database").disabled);
    assert.equal(await page.locator("#db-password").inputValue(),""); assert.equal(await page.locator("#candidate-results").isVisible(),false);
    assert.equal(requests.length,1);
});
