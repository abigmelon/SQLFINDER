package com.sqlfinder.settings;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import com.sqlfinder.chat.application.RequestExecution;
import com.sqlfinder.chat.application.RequestStoppedException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 模型请求传输测试 */

class DeepSeekHttpTransportTest {
    @Test void stoppingLiveHttpRequestReturnsBeforeRemoteHandlerFinishes() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { exchange.sendResponseHeaders(200, -1); } finally { exchange.close(); }
        });
        server.start();
        var execution = new RequestExecution(UUID.randomUUID(), "test-reader", Duration.ofSeconds(10));
        var transport = new DeepSeekHttpTransport(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        var task = new FutureTask<DeepSeekTransport.Response>(() -> {
            try (var scope = execution.bind()) {
                return transport.send("/chat/completions", new ModelConfiguration("deepseek-chat", "stub-key", 10), "{}");
            }
        });
        var worker = new Thread(task);
        worker.start();
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            execution.cancel();
            var failure = assertThrows(ExecutionException.class, () -> task.get(2, TimeUnit.SECONDS));
            assertEquals("REQUEST_CANCELLED", assertInstanceOf(RequestStoppedException.class, failure.getCause()).code());
            assertEquals(1, release.getCount(), "Local stop must finish while the remote handler is still waiting");
            assertFalse(failure.getCause() instanceof java.io.IOException);
        } finally {
            release.countDown();
            server.stop(0);
            worker.join(3000);
        }
    }

    @Test void totalDeadlineBoundsLiveHttpRequest() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        server.createContext("/models", exchange -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        var transport = new DeepSeekHttpTransport(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        var execution = new RequestExecution(UUID.randomUUID(), "test-reader", Duration.ofMillis(350));
        try (var scope = execution.bind()) {
            long start = System.nanoTime();
            var failure = assertThrows(RequestStoppedException.class, () -> transport.send("/models",
                    new ModelConfiguration("deepseek-chat", "stub-key", 10), null));
            assertEquals("REQUEST_TIMEOUT", failure.code());
            assertEquals(0, started.getCount());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void realHttpUsesBearerGetPostUtf8AndNeverFollowsRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls = new AtomicInteger();
        server.createContext("/models", exchange -> {
            calls.incrementAndGet(); assertEquals("GET", exchange.getRequestMethod());
            assertEquals("Bearer stub-key", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Location", "/leak"); exchange.sendResponseHeaders(302,-1); exchange.close();
        });
        server.createContext("/leak", exchange -> { calls.addAndGet(100); exchange.sendResponseHeaders(200,-1); exchange.close(); });
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet(); assertEquals("POST",exchange.getRequestMethod());
            assertEquals("问数",new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes = "回答".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var transport = new DeepSeekHttpTransport(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var config = new ModelConfiguration("deepseek-chat","stub-key",5);
            assertEquals(302,transport.send("/models",config,null).status()); assertEquals(1,calls.get());
            assertEquals("回答",transport.send("/chat/completions",config,"问数").body()); assertEquals(2,calls.get());
            assertThrows(IllegalArgumentException.class, () -> transport.send("https://example.com",config,null));
        } finally { server.stop(0); }
    }
}
