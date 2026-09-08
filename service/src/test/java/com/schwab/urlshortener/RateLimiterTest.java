package com.schwab.urlshortener;

import com.schwab.testlib.Test;
import com.schwab.urlshortener.rate.RateLimiter;

import static com.schwab.testlib.Assert.assertFalse;
import static com.schwab.testlib.Assert.assertTrue;

public class RateLimiterTest {

    @Test
    public void allowsRequestsUpToCapacity() {
        RateLimiter limiter = new RateLimiter(5, 0.0001, 60_000);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryConsume("client-a"), "request " + i + " should be within capacity");
        }
    }

    @Test
    public void rejectsRequestsBeyondCapacityWithNoRefill() {
        RateLimiter limiter = new RateLimiter(3, 0.0001, 60_000);
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("client-b");
        }
        assertFalse(limiter.tryConsume("client-b"), "4th request should be rejected once capacity is exhausted");
    }

    @Test
    public void tracksDistinctClientsIndependently() {
        RateLimiter limiter = new RateLimiter(1, 0.0001, 60_000);
        assertTrue(limiter.tryConsume("client-x"), "first client's first request should pass");
        assertTrue(limiter.tryConsume("client-y"), "second client's first request should pass independently");
        assertFalse(limiter.tryConsume("client-x"), "first client's second request should be rejected");
    }

    @Test
    public void refillsOverTime() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(1, 20.0, 60_000); // refills at 20 tokens/sec
        assertTrue(limiter.tryConsume("client-z"), "initial token should be available");
        assertFalse(limiter.tryConsume("client-z"), "bucket should be empty immediately after");
        Thread.sleep(100); // ~2 tokens worth of refill time at 20/sec
        assertTrue(limiter.tryConsume("client-z"), "token should have refilled after waiting");
    }
}
