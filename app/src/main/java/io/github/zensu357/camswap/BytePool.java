package io.github.zensu357.camswap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Map;

public final class BytePool {
    private static final Map<Integer, ConcurrentLinkedDeque<byte[]>> pools = new ConcurrentHashMap<>();
    private static final int MAX_POOL_SIZE = 8;

    private BytePool() {}

    public static byte[] acquire(int size) {
        if (size <= 0) return new byte[0];
        ConcurrentLinkedDeque<byte[]> pool = pools.get(size);
        if (pool != null) {
            byte[] buf = pool.poll();
            if (buf != null) {
                return buf;
            }
        }
        return new byte[size];
    }

    public static void release(byte[] buffer) {
        if (buffer == null || buffer.length == 0) return;
        int size = buffer.length;
        ConcurrentLinkedDeque<byte[]> pool = pools.computeIfAbsent(size, k -> new ConcurrentLinkedDeque<>());
        if (pool.size() < MAX_POOL_SIZE) {
            pool.offer(buffer);
        }
    }

    public static void clear() {
        pools.clear();
    }
}
