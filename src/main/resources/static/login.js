/* 登录交互：表单认证、CSRF、会话识别和失败恢复；密码只用于本次提交。 */
"use strict";
(() => {
    const { RequestError, requestJson, post } = window.SqlfinderAuth;
    const el = (id) => document.getElementById(id);
    let busy = true;
    function message(text) {
        el("login-message").textContent = text;
        el("login-message").hidden = !text;
    }
    function setBusy(value) {
        busy = value;
        for (const id of ["login-button", "username", "password"]) el(id).disabled = value;
        el("login-button").firstElementChild.textContent = value ? "请稍候…" : "登录工作台";
        el("login-form").setAttribute("aria-busy", String(value));
    }
    const reason = new URLSearchParams(location.search).get("reason");
    if (["expired", "loggedout"].includes(reason)) {
        el("login-notice").textContent = reason === "expired" ? "登录状态已失效，请重新登录。" : "你已退出登录。";
        el("login-notice").hidden = false;
    }
    el("login-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        if (busy) return;
        message("");
        const username = el("username").value.trim();
        const password = el("password").value;
        if (!username || !password) {
            message("请输入用户名和密码。");
            el(!username ? "username" : "password").focus();
            return;
        }
        setBusy(true);
        try {
            const data = await post("/api/auth/login", { headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: new URLSearchParams({ username, password }).toString() });
            if (typeof data?.username !== "string" || !data.username) throw new RequestError("protocol", "登录响应格式不正确，请重试。");
            // 工作台初始化会重新读取登录后会话，不沿用登录前的 CSRF token。
            el("password").value = "";
            location.replace("/");
        } catch (error) {
            const known = error instanceof RequestError;
            const detail = known && error.requestId ? ` 请求标识：${error.requestId}` : "";
            message((known ? error.message : "无法完成登录，请稍后重试。") + detail);
        } finally {
            el("password").value = "";
            setBusy(false);
        }
    });
    setBusy(true);
    requestJson("/api/auth/me", {}, 10000).then((data) => {
        if (typeof data?.username === "string" && data.username) location.replace("/");
        else message("无法确认登录状态，请重新登录。");
    }).catch((error) => {
        if (error.status !== 401) message(error.message || "无法连接服务，请稍后重试。");
    }).finally(() => { setBusy(false); });
})();
