package com.bookworm.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bookworm.common.web.PageResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

class LoyaltyServiceTest {

    private final PointsTransactionRepository pointsTransactionRepository = mock(PointsTransactionRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LoyaltyService service = new LoyaltyService(pointsTransactionRepository, jdbc);

    @Test
    void getPointsSummaryRetrievesBalanceAndTransactions() {
        Long userId = 100L;
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(userId))).thenReturn(450L);

        PointsTransaction tx = PointsTransaction.create(userId, 50L, PointsTransactionType.REDEEM, -100L);
        Page<PointsTransaction> txPage = new PageImpl<>(List.of(tx));
        when(pointsTransactionRepository.findByUserId(eq(userId), any(Pageable.class))).thenReturn(txPage);

        PointsResponse res = service.getPointsSummary(userId, 0, 10);

        assertThat(res.pointsBalance()).isEqualTo(450L);
        assertThat(res.history().content()).hasSize(1);
        assertThat(res.history().content().get(0).points()).isEqualTo(-100L);
    }
}
