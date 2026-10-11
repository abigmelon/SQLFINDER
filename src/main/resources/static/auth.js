/* 会话客户端：同源 Cookie、JSON 错误和每次写请求的 CSRF；不保存凭据，不自动重试。 */
"use strict";
(() => {
    class RequestError extends Error {
        constructor(kind, message, code = "", requestId = "", status = 0) {
            super(message);
            Object.assign(this, { kind, code, requestId, status });
        }
    }
    async function requestJson(url, options = {}, timeout = 120000) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeout);
        try {
            const headers = new Headers(options.headers);
            if (!headers.has("Accept")) headers.set("Accept", "application/json");
            const response = await fetch(url, { ...options, headers, credentials: "same-origin", signal: controller.signal });
            let data;
            try { data = await response.json(); }
            catch (error) {
                if (controller.signal.aborted) throw error;
                throw new RequestError("protocol", "服务返回的响应格式不正确，请稍后重试。",
                    response.ok ? "" : `HTTP ${response.status}`, "", response.status);
            }
            if (!response.ok) {
                const text = (key) => typeof data?.[key] === "string" ? data[key] : "";
                throw new RequestError("http", text("message") || "请求未能完成，请稍后重试。",
                    text("errorCode") || `HTTP ${response.status}`, text("requestId"), response.status);
            }
            return data;
        } catch (error) {
            if (error instanceof RequestError) throw error;
            throw new RequestError("network", controller.signal.aborted
                ? "等待响应超时，请稍后重试。服务端可能仍在处理这次请求。"
                : "无法连接服务，请检查网络或服务是否启动。", controller.signal.aborted ? "CLIENT_TIMEOUT" : "");
        } finally { clearTimeout(timer); }
    }
    async function post(url, options = {}) {
        // 登录会使旧 token 失效，因此每次读取当前会话的 token。
        const csrf = await requestJson("/api/auth/csrf", {}, 10000);
        if (typeof csrf?.headerName !== "string" || !/^[A-Za-z0-9-]+$/.test(csrf.headerName)
            || typeof csrf.token !== "string" || !csrf.token) {
            throw new RequestError("protocol", "无法获取安全校验信息，请刷新页面后重试。");
        }
        const headers = new Headers(options.headers);
        headers.set(csrf.headerName, csrf.token);
        return requestJson(url, { ...options, method: "POST", headers });
    }
    function loginUrl(reason = "") {
        return "/login.html" + (["expired", "loggedout"].includes(reason) ? `?reason=${reason}` : "");
    }
    window.SqlfinderAuth = { RequestError, requestJson, post, loginUrl };
})();
