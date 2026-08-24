package io.github.zensu357.camswap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BytePoolTest {

    @Before
    public void setUp() {
        BytePool.clear();
    }

    @After
    public void tearDown() {
        BytePool.clear();
    }

    @Test
    public void testAcquireAndRelease() {
        int size = 1024;
        byte[] buf1 = BytePool.acquire(size);
        assertNotNull(buf1);
        assertEquals(size, buf1.length);

        BytePool.release(buf1);
        byte[] buf2 = BytePool.acquire(size);
        assertNotNull(buf2);
        assertEquals(size, buf2.length);
    }

    @Test
    public void testConcurrentAcquireAndRelease() throws InterruptedException {
        int threadCount = 8;
        int iterations = 1000;
        int size = 4096;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successfulOps = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        byte[] buffer = BytePool.acquire(size);
                        if (buffer != null && buffer.length == size) {
                            successfulOps.incrementAndGet();
                        }
                        BytePool.release(buffer);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(finished);
        assertEquals(threadCount * iterations, successfulOps.get());
    }

    @Test
    public void testInvalidSizes() {
        byte[] empty = BytePool.acquire(0);
        assertNotNull(empty);
        assertEquals(0, empty.length);

        byte[] negative = BytePool.acquire(-10);
        assertNotNull(negative);
        assertEquals(0, negative.length);

        BytePool.release(null);
        BytePool.release(new byte[0]);
    }
}
