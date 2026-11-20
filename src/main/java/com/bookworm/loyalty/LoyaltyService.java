package com.bookworm.loyalty;

import com.bookworm.common.web.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoyaltyService {

    private final PointsTransactionRepository pointsTransactionRepository;
    private final JdbcTemplate jdbc;

    public LoyaltyService(PointsTransactionRepository pointsTransactionRepository, JdbcTemplate jdbc) {
        this.pointsTransactionRepository = pointsTransactionRepository;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PointsResponse getPointsSummary(Long userId, int page, int size) {
        Long balance = jdbc.queryForObject("SELECT points_balance FROM users WHERE id = ?", Long.class, userId);
        long pointsBalance = balance != null ? balance : 0L;

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PointsTransaction> txPage = pointsTransactionRepository.findByUserId(userId, pageable);

        PageResponse<PointsTransactionResponse> history = PageResponse.of(txPage.map(PointsTransactionResponse::from));

        return new PointsResponse(pointsBalance, history);
    }
}
