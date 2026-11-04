package com.shop.b2b2c.config;

import com.shop.b2b2c.mapper.IdempotencyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * R27 单元测试：验证 IdempotencyPurgeJob 的批量清理逻辑与截止时间语义。
 *
 * 覆盖：
 *   TC-R27-01  单批未满时仅执行一次删除
 *   TC-R27-02  首批满额、第二批耗尽——共循环两次
 *   TC-R27-03  连续两批满额、第三批耗尽——共循环三次
 *   TC-R27-04  无可删记录时不触发多余删除
 *   TC-R27-05  purge() 传入的截止时间约等于 now() - retentionHours（误差 ≤ 5s）
 *   TC-R27-06  batchSize 参数正确传递给 Mapper
 *
 * 注意：测试类与被测类同属 com.shop.b2b2c.config 包，
 *       以访问 purgeWithCutoff(LocalDateTime) 包级可见方法。
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyPurgeJobTest {

    @Mock
    private IdempotencyMapper idempotencyMapper;

    @InjectMocks
    private IdempotencyPurgeJob purgeJob;

    private static final int BATCH_SIZE = 500;
    private static final int RETENTION_HOURS = 72;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(purgeJob, "retentionHours", RETENTION_HOURS);
        ReflectionTestUtils.setField(purgeJob, "batchSize", BATCH_SIZE);
    }

    /**
     * TC-R27-01：当单批删除数 < batchSize 时，循环只执行一次；
     * 验证总删除量 == 实际返回值。
     */
    @Test
    void purgesExpiredCompletedRecords_singleBatch() {
        int deleted = 123;
        LocalDateTime cutoff = LocalDateTime.now().minusHours(RETENTION_HOURS);
        when(idempotencyMapper.deleteExpiredBefore(eq(cutoff), eq(BATCH_SIZE)))
                .thenReturn(deleted);

        int total = purgeJob.purgeWithCutoff(cutoff);

        assertThat(total).isEqualTo(deleted);
        verify(idempotencyMapper, times(1))
                .deleteExpiredBefore(eq(cutoff), eq(BATCH_SIZE));
    }

    /**
     * TC-R27-02：首批返回 batchSize（满额）、第二批返回 0 时，
     * 应循环两次，总删除量 == batchSize。
     */
    @Test
    void purgesInBatchesUntilExhausted_twoBatches() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(RETENTION_HOURS);
        when(idempotencyMapper.deleteExpiredBefore(eq(cutoff), eq(BATCH_SIZE)))
                .thenReturn(BATCH_SIZE)   // 第一批：满额，继续
                .thenReturn(0);           // 第二批：耗尽，退出

        int total = purgeJob.purgeWithCutoff(cutoff);

        assertThat(total).isEqualTo(BATCH_SIZE);
        verify(idempotencyMapper, times(2))
                .deleteExpiredBefore(eq(cutoff), eq(BATCH_SIZE));
    }

    /**
     * TC-R27-03：连续两批满额、第三批耗尽时，共循环三次，累计 2 * batchSize。
     */
    @Test
    void purgesMultipleBatches_untilZero() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(RETENTION_HOURS);
        when(idempotencyMapper.deleteExpiredBefore(eq(cutoff), eq(BATCH_SIZE)))
                .thenReturn(BATCH_SIZE)
                .thenReturn(BATCH_SIZE)
                .thenReturn(0);

        int total = purgeJob.purgeWithCutoff(cutoff);

        assertThat(total).isEqualTo(2 * BATCH_SIZE);
        verify(idempotencyMapper, times(3))
                .deleteExpiredBefore(eq(cutoff), eq(BATCH_SIZE));
    }

    /**
     * TC-R27-04：无可删记录时，调用一次 deleteExpiredBefore 返回 0，总量为 0。
     */
    @Test
    void doesNotDeleteIfNothingExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(RETENTION_HOURS);
        when(idempotencyMapper.deleteExpiredBefore(any(LocalDateTime.class), eq(BATCH_SIZE)))
                .thenReturn(0);

        int total = purgeJob.purgeWithCutoff(cutoff);

        assertThat(total).isEqualTo(0);
        verify(idempotencyMapper, times(1))
                .deleteExpiredBefore(any(LocalDateTime.class), eq(BATCH_SIZE));
    }

    /**
     * TC-R27-05：purge() 公开方法传入的截止时间应约等于 now() - retentionHours
     * （误差 ≤ 5 秒），验证不早于保留期以上、也不超过当前时间。
     */
    @Test
    void cutoffIsRetentionHoursBeforeNow() {
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(idempotencyMapper.deleteExpiredBefore(captor.capture(), eq(BATCH_SIZE)))
                .thenReturn(0);

        LocalDateTime before = LocalDateTime.now();
        purgeJob.purge();
        LocalDateTime after = LocalDateTime.now();

        LocalDateTime capturedCutoff = captor.getValue();
        LocalDateTime expectedLower = before.minusHours(RETENTION_HOURS).minusSeconds(5);
        LocalDateTime expectedUpper = after.minusHours(RETENTION_HOURS).plusSeconds(5);
        assertThat(capturedCutoff).isAfterOrEqualTo(expectedLower);
        assertThat(capturedCutoff).isBeforeOrEqualTo(expectedUpper);
    }

    /**
     * TC-R27-06：验证 batchSize 参数被正确传递给 Mapper。
     */
    @Test
    void passesBatchSizeToMapper() {
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        when(idempotencyMapper.deleteExpiredBefore(any(LocalDateTime.class), limitCaptor.capture()))
                .thenReturn(0);

        purgeJob.purge();

        assertThat(limitCaptor.getValue()).isEqualTo(BATCH_SIZE);
    }
}
