package com.bookworm.loyalty;

import com.bookworm.common.web.PageResponse;

public record PointsResponse(
        long pointsBalance,
        PageResponse<PointsTransactionResponse> history) {
}
