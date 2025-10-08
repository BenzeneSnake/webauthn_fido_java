package com.webauthn.app.infrastructure.retry;

import java.util.function.Supplier;

public class RetryUtils {
    public static <T> T executeWithRetry(Supplier<T> action,
                                         int maxAttempts,
                                         long initialDelayMillis) throws Exception {
        int attempt = 1;
        Exception lastException = null;

        while (attempt <= maxAttempts) {
            try {
                return action.get(); // 嘗試執行
            } catch (Exception e) {
                lastException = e;
                if (attempt == maxAttempts) break;

                long backoffTime = initialDelayMillis * (1L << (attempt - 1)); // 指數遞增: 1s, 2s, 4s...
                Thread.sleep(backoffTime);
                attempt++;
            }
        }
        throw lastException; // 全部失敗才丟出
    }
}
