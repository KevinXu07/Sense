package com.zephiel.sense.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class RootUtils {

    private static Process shell;
    private static BufferedWriter writer;
    private static BufferedReader reader;
    private static Thread readerThread;
    private static final BlockingQueue<String> lineQueue = new LinkedBlockingQueue<>();

    private static final Object lock = new Object();
    private static ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "RootUtils-Executor"));

    private static volatile boolean debug = false;
    private static final AtomicInteger CMD_ID = new AtomicInteger(0);

    public static void setDebugLogging(boolean enabled) {
        debug = enabled;
    }

    public static boolean isRootAvailable() {
        try {
            CommandResult result = exec("id -u", 2000);
            return result != null && "0".equals(result.output.trim());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 兼容旧 API：强制刷新 root shell 并检查权限
     * 增加重试机制，确保在系统启动初期能有足够的时间等待 SU 进程就绪
     */
    public static boolean checkRootAccessFresh() {
        int retries = 10;
        while (retries > 0) {
            synchronized (lock) {
                closeShellInternal();
                ensureShell();
                if (isRootAvailable()) {
                    SenseLog.i("Root access confirmed (attempt %d)", 11 - retries);
                    return true;
                }
            }
            retries--;
            if (retries > 0) {
                SenseLog.w("Root access not ready, retrying in 3s... (%d left)", retries);
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
        SenseLog.e("Root access check failed after 10 attempts");
        return false;
    }


    public static void execAsync(String command) {
        executor.execute(() -> exec(command));
    }

    public static void execAsync(String command, Consumer<CommandResult> callback) {
        CompletableFuture.supplyAsync(() -> exec(command), executor)
                .thenAccept(callback);
    }

    public static CompletableFuture<CommandResult> execFuture(String command) {
        return CompletableFuture.supplyAsync(() -> exec(command), executor);
    }

    public static CommandResult exec(String command) {
        return exec(command, 5000);
    }

    /**
     * 优化点：移除每次执行前的健康检查，仅在发生异常时尝试恢复。
     */
    public static CommandResult exec(String command, long timeoutMs) {
        synchronized (lock) {
            ensureShell();

            if (writer == null) return null;

            int id = CMD_ID.incrementAndGet();
            String marker = "__END__" + id;

            try {
                // 合并写入，减少系统调用
                writer.write(command + "\necho " + marker + ":$?\n");
                writer.flush();
                
                StringBuilder output = new StringBuilder();
                long deadline = System.currentTimeMillis() + timeoutMs;

                while (true) {
                    long now = System.currentTimeMillis();
                    if (now > deadline) throw new TimeoutException("Command timeout: " + command);

                    long remaining = deadline - now;
                    String line = lineQueue.poll(Math.max(20, Math.min(200, remaining)), TimeUnit.MILLISECONDS);
                    if (line == null) continue;

                    if (line.startsWith(marker)) {
                        int exitCode = parseExitCode(line);
                        return new CommandResult(output.toString().trim(), exitCode);
                    }

                    output.append(line).append("\n");
                }

            } catch (Exception e) {
                SenseLog.e("Exec error, closing shell: %s", e, command);
                closeShellInternal();
            }

            return null;
        }
    }

    private static void ensureShell() {
        if (shell != null && shell.isAlive() && writer != null) return;

        closeShellInternal();

        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "RootUtils-Executor"));
        }

        try {
            shell = Runtime.getRuntime().exec("su");
            writer = new BufferedWriter(new OutputStreamWriter(shell.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(shell.getInputStream(), StandardCharsets.UTF_8));

            writer.write("exec 2>&1\n");
            writer.flush();

            lineQueue.clear();
            readerThread = new Thread(() -> {
                try {
                    String l;
                    while ((l = reader.readLine()) != null) {
                        lineQueue.offer(l);
                    }
                } catch (IOException ignored) {}
            }, "RootUtils-Reader");
            readerThread.setDaemon(true);
            readerThread.start();

        } catch (IOException e) {
            closeShellInternal();
        }
    }

    public static void closeShell() {
        synchronized (lock) {
            closeShellInternal();
            if (executor != null) {
                try {
                    executor.shutdownNow();
                    executor = null;
                } catch (Exception ignored) {}
            }
        }
    }

    private static void closeShellInternal() {
        try {
            if (readerThread != null) {
                readerThread.interrupt();
            }
        } catch (Exception ignored) {}

        try { lineQueue.clear(); } catch (Exception ignored) {}

        try {
            if (writer != null) {
                writer.write("exit\n");
                writer.flush();
                writer.close();
            }
        } catch (Exception ignored) {}

        if (shell != null) {
            try {
                shell.destroy();
                if (!shell.waitFor(100, TimeUnit.MILLISECONDS)) {
                    shell.destroyForcibly();
                }
            } catch (Exception ignored) {
                if (shell != null) shell.destroyForcibly();
            }
        }

        shell = null;
        writer = null;
        reader = null;
        readerThread = null;
    }

    private static int parseExitCode(String line) {
        try {
            int idx = line.lastIndexOf(":");
            return Integer.parseInt(line.substring(idx + 1).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    public static class CommandResult {
        public final String output;
        public final int exitCode;

        public CommandResult(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
