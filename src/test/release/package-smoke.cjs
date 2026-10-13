/* 发布包验收 */
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const fsSync = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const { spawn, spawnSync } = require("node:child_process");
const { chromium } = require("playwright");
const root = path.resolve(process.argv[2]);
const restored = path.resolve(process.argv[3]);
const java = path.join(process.env.JAVA_HOME, "bin/java.exe");
const id = "11111111-2222-3333-4444-555555555555";
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function start(directory) {
    const socket = http.createServer();
    await new Promise(resolve => socket.listen(0, "127.0.0.1", resolve));
    const port = socket.address().port;
    await new Promise(resolve => socket.close(resolve));
    const log = fsSync.openSync(path.join(directory, "release-startup.log"), "a");
    const child = spawn(java, ["-jar", path.join(directory, "sqlfinder-server-0.1.0.jar"),
        `--server.port=${port}`, "--spring.datasource.hikari.connection-timeout=1000",
        "--spring.datasource.hikari.initialization-fail-timeout=-1"], { cwd: directory, stdio: ["ignore", log, log], windowsHide: true,
            env: { ...process.env, DEEPSEEK_API_KEY: "", SQLFINDER_DB_USERNAME: "unconfigured", SQLFINDER_DB_PASSWORD: "",
                SQLFINDER_DB_URL: "jdbc:mysql://127.0.0.1:1/unconfigured?connectTimeout=500&socketTimeout=500",
                SQLFINDER_HISTORY_PATH: path.join(directory, "data/history") } });
    fsSync.closeSync(log);
    const origin = `http://127.0.0.1:${port}`;
    let ready = false;
    for (let i = 0; i < 120; i++) {
        if (child.exitCode !== null) break;
        try { ready = (await fetch(origin + "/health")).ok; } catch {}
        if (ready) break;
        await delay(500);
    }
    if (!ready) { await stop(child); throw Error("Extracted release failed to start"); }
    return { origin, child };
}
async function stop(child) {
    if (child.exitCode !== null || child.signalCode !== null) return;
    const ended = new Promise(resolve => child.once("exit", resolve));
    child.kill(); await ended;
}
async function check(directory, browser) {
    const { child, origin } = await start(directory);
    let context;
    try {
        context = await browser.newContext({ viewport: { width: 960, height: 920 } });
        const page = await context.newPage();
        await page.goto(origin);
        await page.waitForURL("**/login.html*");
        await page.locator("#username").fill("release-test");
        await page.locator("#password").fill("release-test-only-password");
        await page.locator("#login-button").click();
        await page.waitForURL(origin + "/");
        const run = await context.request.get(`${origin}/api/chat/requests/${id}`);
        assert.equal(run.status(), 200);
        assert.equal((await run.json()).status, "QUERY_SUCCEEDED");
        const diagnostic = await context.request.get(`${origin}/api/system/diagnostics`);
        assert.equal(diagnostic.status(), 200);
        const report = await diagnostic.json();
        assert.equal(report.history.status, "READY");
        assert.equal(report.model.status, "NOT_CONFIGURED");
        await page.goto(origin + "/settings.html");
        assert.equal((await page.title()).includes("设置"), true);
        await page.screenshot({ path: path.join(directory, "smoke-settings.png") });
        console.log("EXTRACTED_RELEASE_LOGIN_AUDIT_DIAGNOSTICS_VERIFIED");
    } finally { try { if (context) await context.close(); } finally { await stop(child); } }
}
async function main() {
    const browser = await chromium.launch({ headless: true, channel: "msedge" });
    try {
        await check(root, browser);
        const backup = path.join(path.dirname(root), "release-smoke-backup.zip");
        const ps = (script, args) => {
            const result = spawnSync("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                path.join(root, "scripts", script), ...args], { encoding: "utf8", windowsHide: true });
            assert.equal(result.status, 0, result.stderr || "Release script failed");
        };
        ps("backup.ps1", ["-Root", root, "-BackupPath", backup]);
        await fs.mkdir(restored, { recursive: true });
        await fs.copyFile(path.join(root, "sqlfinder-server-0.1.0.jar"), path.join(restored, "sqlfinder-server-0.1.0.jar"));
        ps("restore.ps1", ["-Root", restored, "-BackupPath", backup]);
        assert.deepEqual(await fs.readFile(path.join(root, "data/history.mv.db")), await fs.readFile(path.join(restored, "data/history.mv.db")));
        await check(restored, browser);
        console.log("OFFLINE_BACKUP_RESTORE_RESTART_VERIFIED");
    } finally { await browser.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
