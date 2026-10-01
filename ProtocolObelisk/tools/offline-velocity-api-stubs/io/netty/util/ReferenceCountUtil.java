package io.netty.util;
public final class ReferenceCountUtil {
    private ReferenceCountUtil() {}
    public static boolean release(Object message) {
        return message instanceof ReferenceCounted counted && counted.release();
    }
    public static void safeRelease(Object message) {
        try {
            release(message);
        } catch (Throwable ignored) {
            // Test-only parity with Netty: cleanup must never replace the original failure.
        }
    }
}
