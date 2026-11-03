/*
 * 文件：ReadinessService.java
 * 用途：有界检查数据库连通性、Redis响应与持久化健康，供就绪探针使用。
 * 维护注意：仅SELECT 1、PING及INFO，不写业务数据；1.5秒等待预算、两条无队列工作线程、1秒缓存限制故障放大。
 * 驱动不响应中断时线程仍被占用，但不会无限创建或排队；超时/拒绝/未知指标均DOWN，不返回异常或凭据。
 * 不代表Schema完整、Redis可写权限、渠道可用或财务验收；Redis ACL须允许PING和INFO persistence。
 */
package com.shop.b2b2c.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Properties;
import java.util.concurrent.*;

@Service
public class ReadinessService implements AutoCloseable {
    public record Report(String status, String database, String redis) {
        public boolean ready() { return "UP".equals(status); }
    }
    private record Sample(Report report, long expiresAt) { }
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ExecutorService workers;
    private final long budgetNanos;
    private final long cacheNanos;
    private volatile Sample sample;

    @Autowired
    public ReadinessService(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this(jdbc, redis, 1500, 1000);
    }

    // 包内参数仅供隔离测试缩短等待，不改全应用连接池或Redis超时。
    ReadinessService(JdbcTemplate jdbc, StringRedisTemplate redis, long budgetMillis, long cacheMillis) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        this.cacheNanos = TimeUnit.MILLISECONDS.toNanos(cacheMillis);
        this.workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "readiness-probe");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public Report check() {
        Sample cached = sample;
        if (cached != null && System.nanoTime() - cached.expiresAt() < 0) return cached.report();
        return refresh();
    }

    private synchronized Report refresh() {
        Sample cached = sample;
        if (cached != null && System.nanoTime() - cached.expiresAt() < 0) return cached.report();
        long deadline = System.nanoTime() + budgetNanos;
        Future<Boolean> database = submit(this::databaseUp);
        Future<Boolean> cache = submit(this::redisUp);
        boolean dbUp = result(database, deadline), redisUp = result(cache, deadline);
        Report report = new Report(dbUp && redisUp ? "UP" : "DOWN", dbUp ? "UP" : "DOWN", redisUp ? "UP" : "DOWN");
        sample = new Sample(report, System.nanoTime() + cacheNanos);
        return report;
    }

    private Future<Boolean> submit(Callable<Boolean> check) {
        try { return workers.submit(check); }
        catch (RejectedExecutionException unavailable) { return null; }
    }

    private boolean result(Future<Boolean> task, long deadline) {
        if (task == null) return false;
        try { return Boolean.TRUE.equals(task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (ExecutionException | TimeoutException | CancellationException unavailable) { return false; }
        finally { if (!task.isDone()) task.cancel(true); }
    }

    private boolean databaseUp() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(1);
                try (var rows = statement.executeQuery("SELECT 1")) {
                    return rows.next() && rows.getInt(1) == 1;
                }
            }
        }));
    }

    private boolean redisUp() {
        return Boolean.TRUE.equals(redis.execute((RedisCallback<Boolean>) connection ->
                "PONG".equals(connection.ping()) && persistenceUp(connection.serverCommands().info("persistence"))));
    }

    static boolean persistenceUp(Properties info) {
        if (info == null || !"0".equals(info.getProperty("loading"))
                || !"ok".equals(info.getProperty("rdb_last_bgsave_status"))) return false;
        return switch (info.getProperty("aof_enabled", "")) {
            case "0" -> true;
            case "1" -> "ok".equals(info.getProperty("aof_last_write_status"))
                    && "ok".equals(info.getProperty("aof_last_bgrewrite_status"));
            default -> false;
        };
    }

    @Override
    @PreDestroy
    public void close() { workers.shutdownNow(); }
}
