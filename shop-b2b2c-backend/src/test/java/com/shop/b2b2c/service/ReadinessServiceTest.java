/*
 * 文件：ReadinessServiceTest.java
 * 用途：隔离验证就绪探针的SQL/Redis检查、失败摘要、资源释放、缓存和有界故障行为。
 * 维护注意：JDBC/Redis全部Mock；测试故意模拟不响应中断的依赖并用锁闩释放，不访问业务环境。耗时断言只验证等待有上界，不替代真实驱动/Redis故障联调。
 */
package com.shop.b2b2c.service;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadinessServiceTest {
    JdbcTemplate jdbc;
    StringRedisTemplate redis;
    Connection database;
    Statement statement;
    ResultSet rows;
    RedisConnection connection;
    RedisServerCommands commands;
    ReadinessService service;

    static Properties healthy() {
        var info = new Properties();
        info.setProperty("loading", "0"); info.setProperty("rdb_last_bgsave_status", "ok");
        info.setProperty("aof_enabled", "0");
        return info;
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc = mock(JdbcTemplate.class); redis = mock(StringRedisTemplate.class);
        database = mock(Connection.class); statement = mock(Statement.class); rows = mock(ResultSet.class);
        when(database.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(rows);
        when(rows.next()).thenReturn(true); when(rows.getInt(1)).thenReturn(1);
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any())).thenAnswer(call -> {
            ConnectionCallback<Boolean> callback = call.getArgument(0);
            return callback != null ? callback.doInConnection(database) : null;
        });
        connection = mock(RedisConnection.class); commands = mock(RedisServerCommands.class);
        when(connection.ping()).thenReturn("PONG"); when(connection.serverCommands()).thenReturn(commands);
        when(commands.info("persistence")).thenReturn(healthy());
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisCallback<Boolean>>any())).thenAnswer(call -> {
            RedisCallback<Boolean> callback = call.getArgument(0);
            return callback != null ? callback.doInRedis(connection) : null;
        });
        service = new ReadinessService(jdbc, redis, 500, 60000);
    }

    @AfterEach
    void close() { service.close(); }

    @Test
    void healthyDependenciesAreUpAndSqlResourcesAreClosed() throws Exception {
        assertEquals(new ReadinessService.Report("UP", "UP", "UP"), service.check());
        verify(statement).setQueryTimeout(1); verify(statement).close(); verify(rows).close();
        verify(connection).ping(); verify(commands).info("persistence");
    }

    @Test
    void bothDependencyFailuresAreDownWithoutExceptionDetails() {
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any()))
                .thenThrow(new IllegalStateException("private-database-credentials"));
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisCallback<Boolean>>any()))
                .thenThrow(new IllegalStateException("private-redis-credentials"));
        assertEquals(new ReadinessService.Report("DOWN", "DOWN", "DOWN"), service.check());
    }

    @Test
    void wrongSqlResultStillChecksRedisAndClosesResources() throws Exception {
        when(rows.getInt(1)).thenReturn(0);
        assertEquals(new ReadinessService.Report("DOWN", "DOWN", "UP"), service.check());
        verify(rows).close(); verify(statement).close(); verify(commands).info("persistence");
    }

    @Test
    void unexpectedPingAndMissingInfoFailClosed() {
        when(connection.ping()).thenReturn("UNKNOWN");
        assertEquals(new ReadinessService.Report("DOWN", "UP", "DOWN"), service.check());
        verifyNoInteractions(commands);
        assertFalse(ReadinessService.persistenceUp(null));
        assertFalse(ReadinessService.persistenceUp(new Properties()));
    }

    @Test
    void loadingRdbAndAofFailuresAndUnknownMetricsAreNotReady() {
        for (String[] invalid : List.of(new String[]{"loading", "1"}, new String[]{"loading", "unknown"},
                new String[]{"rdb_last_bgsave_status", "err"}, new String[]{"aof_enabled", "unknown"})) {
            var info = healthy(); info.setProperty(invalid[0], invalid[1]);
            assertFalse(ReadinessService.persistenceUp(info), Arrays.toString(invalid));
        }
        var aof = healthy(); aof.setProperty("aof_enabled", "1");
        assertFalse(ReadinessService.persistenceUp(aof));
        aof.setProperty("aof_last_write_status", "ok"); aof.setProperty("aof_last_bgrewrite_status", "ok");
        assertTrue(ReadinessService.persistenceUp(aof));
        aof.setProperty("aof_last_write_status", "err"); assertFalse(ReadinessService.persistenceUp(aof));
        aof.setProperty("aof_last_write_status", "ok"); aof.setProperty("aof_last_bgrewrite_status", "err");
        assertFalse(ReadinessService.persistenceUp(aof));
    }

    @Test
    void persistenceErrorMakesTheWholeProbeUnavailableEvenWhenPingWorks() {
        var info = healthy(); info.setProperty("rdb_last_bgsave_status", "err");
        when(commands.info("persistence")).thenReturn(info);
        assertEquals(new ReadinessService.Report("DOWN", "UP", "DOWN"), service.check());
    }

    @Test
    void repeatedAndConcurrentCallsShareTheFreshSample() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            var requests = new ArrayList<Future<ReadinessService.Report>>();
            for (int i = 0; i < 8; i++) requests.add(callers.submit(service::check));
            for (var request : requests) assertTrue(request.get(2, TimeUnit.SECONDS).ready());
            assertTrue(service.check().ready());
            verify(jdbc, times(1)).execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any());
            verify(redis, times(1)).execute(org.mockito.ArgumentMatchers.<RedisCallback<Boolean>>any());
        } finally { callers.shutdownNow(); }
    }

    @Test
    void blockedDependenciesCannotCreateUnboundedWorkOrBlockRequestsForever() {
        service.close(); service = new ReadinessService(jdbc, redis, 500, 0);
        var release = new CountDownLatch(1);
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any()))
                .thenAnswer(call -> { awaitIgnoringInterrupts(release); return true; });
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisCallback<Boolean>>any()))
                .thenAnswer(call -> { awaitIgnoringInterrupts(release); return true; });
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                for (int i = 0; i < 5; i++) assertFalse(service.check().ready());
            });
            verify(jdbc, times(1)).execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any());
            verify(redis, times(1)).execute(org.mockito.ArgumentMatchers.<RedisCallback<Boolean>>any());
        } finally { release.countDown(); }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean finished = false;
        while (!finished) {
            try { latch.await(); finished = true; }
            catch (InterruptedException ignored) { /* 模拟驱动暂不响应取消，测试finally负责释放。 */ }
        }
    }
}
