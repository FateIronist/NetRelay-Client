package top.fateironist.net_relay.common;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class AsyncIoThreadPool {
    private static ThreadFactory threadFactory = new ThreadFactory() {
        private AtomicInteger threadNumber = new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("AsyncIoThreadPool-" + threadNumber);
            return thread;
        }
    };

    private static ExecutorService executorService = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().availableProcessors() * 2,
            1,
            TimeUnit.MINUTES,
            new LinkedBlockingQueue<>(100),
            threadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public static void execute(Runnable runnable) {
        executorService.execute(runnable);
    }

    public static void executeWithTimeoutIgnoreException(Runnable runnable, long timeout, TimeUnit timeUnit, Consumer<Exception> errorCallback) {
        Future<?> future = executorService.submit(runnable);
        executorService.submit(() -> {
            try {
                future.get(timeout, timeUnit);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    public static void executeWithTimeout(Runnable runnable, long timeout, TimeUnit timeUnit) throws Exception {
        Future<?> future = executorService.submit(runnable);
        future.get(timeout, timeUnit);
    }
}
