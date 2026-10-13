package com.sqlfinder.chat.application;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 请求控制测试 */
class RequestExecutionTest {
    private RequestExecution request() { return new RequestExecution(UUID.randomUUID(), "alice", Duration.ofSeconds(2)); }

    @Test void scopeRestoresNestedThreadContext() {
        assertNull(RequestExecution.current());
        var first = request(); var second = request();
        try (var outer = first.bind()) {
            assertSame(first, RequestExecution.current());
            try (var inner = second.bind()) { assertSame(second, RequestExecution.current()); }
            assertSame(first, RequestExecution.current());
        }
        assertNull(RequestExecution.current());
    }
    @Test void cancellationDispatchesResourcesAndCannotBecomeSuccess() throws Exception {
        var request = request();
        var stopped = new CountDownLatch(1);
        request.onCancel(stopped::countDown);
        request.stage("MODEL_REQUEST");
        request.cancel(); request.cancel();
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        var ex = assertThrows(RequestStoppedException.class, request::check);
        assertEquals("REQUEST_CANCELLED", ex.code());
        assertEquals(request.requestId(), ex.requestId());
        assertThrows(RequestStoppedException.class, () -> request.result("QUERY_SUCCEEDED", null, null, null));
        assertEquals("MODEL_REQUEST", request.snapshot().failureStage());
        assertEquals("CANCELLED", request.snapshot().status());
    }
    @Test void unregisterPreventsCancellingAnAlreadyClosedResource() throws Exception {
        var request = request(); var calls = new AtomicInteger();
        var registration = request.onCancel(calls::incrementAndGet);
        registration.close();
        request.cancel();
        var late = new CountDownLatch(1);
        request.onCancel(late::countDown);
        assertTrue(late.await(2, TimeUnit.SECONDS));
        assertEquals(0, calls.get());
    }
    @Test void timeoutAndUserCancellationKeepTheFirstTerminalReason() {
        var request = request(); request.expire(); request.cancel();
        assertEquals("REQUEST_TIMEOUT", assertThrows(RequestStoppedException.class, request::check).code());
        assertEquals("TIMED_OUT", request.snapshot().status());
    }
    @Test void completedRequestCannotCancelResourcesLater() throws Exception {
        var request = request(); var calls = new AtomicInteger();
        request.onCancel(calls::incrementAndGet);
        request.stage("EXECUTION");
        request.result("QUERY_SUCCEEDED", null, UUID.randomUUID(), UUID.randomUUID());
        request.cancel(); request.expire(); request.check();
        assertEquals("QUERY_SUCCEEDED", request.snapshot().status());
        assertEquals(0, calls.get());
        assertNotNull(request.snapshot().finishedAt());
        assertTrue(request.snapshot().stageDurations().containsKey("EXECUTION"));
    }
}
