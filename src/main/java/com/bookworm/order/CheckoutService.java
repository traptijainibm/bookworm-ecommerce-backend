package com.bookworm.order;

import com.bookworm.loyalty.PointsTransaction;
import com.bookworm.loyalty.PointsTransactionRepository;
import com.bookworm.loyalty.PointsTransactionType;
import com.bookworm.cart.Cart;
import com.bookworm.cart.CartItem;
import com.bookworm.cart.CartItemRepository;
import com.bookworm.cart.CartRepository;
import com.bookworm.catalogue.BookInfo;
import com.bookworm.catalogue.BookLookupService;
import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import com.bookworm.common.web.PageResponse;

/**
 * Atomic checkout orchestrator.
 */
@Service
public class CheckoutService {

    private static final long CHECKOUT_LOCK_NAMESPACE = 0x43484543L; // 'CHEC'
    private static final BigDecimal POINT_VALUE = new BigDecimal("0.10"); // Rs 0.10 per point
    private static final BigDecimal EARN_PER_AMOUNT = new BigDecimal("100.00"); // 1 point per 100 Rs
    private static final BigDecimal MAX_REDEEM_PERCENT = new BigDecimal("0.20"); // 20% cap
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final PaymentRepository paymentRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final BookLookupService bookLookupService;
    private final PaymentGateway paymentGateway;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PointsTransactionRepository pointsTransactionRepository;

    public CheckoutService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            PaymentRepository paymentRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            BookLookupService bookLookupService,
            PaymentGateway paymentGateway,
            JdbcTemplate jdbc,
            Clock clock,
            PointsTransactionRepository pointsTransactionRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.paymentRepository = paymentRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.bookLookupService = bookLookupService;
        this.paymentGateway = paymentGateway;
        this.jdbc = jdbc;
        this.clock = clock;
        this.pointsTransactionRepository = pointsTransactionRepository;
    }

    public record CheckoutResult(OrderResponse order, boolean isReplay) {
    }

    @Transactional
    public CheckoutResult checkout(Long userId, String userEmail, String idempotencyKey, CheckoutRequest request) {
        validateIdempotencyKey(idempotencyKey);

        // 1. Transaction-scoped advisory lock on (userId, idempotencyKey)
        long lockKey = computeAdvisoryLockKey(userId, idempotencyKey);
        orderRepository.acquireAdvisoryLock(lockKey);

        // 2. Check existing order by (userId, idempotencyKey)
        String requestFingerprint = computeFingerprint(request);
        Optional<Order> existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            Order existingOrder = existing.get();
            if (!existingOrder.getRequestFingerprint().equals(requestFingerprint)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key was previously used with a different request payload.");
            }
            List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(existingOrder.getId());
            return new CheckoutResult(OrderResponse.of(existingOrder, items), true);
        }

        // 3. Lock user's cart row
        Cart cart = cartRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.CART_EMPTY, "Cart is empty."));

        List<CartItem> lines = new ArrayList<>(cartItemRepository.findByCartIdOrderByIdAsc(cart.getId()));
        if (lines.isEmpty()) {
            throw new ApiException(ErrorCode.CART_EMPTY, "Cart is empty.");
        }

        // 4. Sort lines in ascending bookId order to prevent deadlocks when locking stock
        lines.sort(Comparator.comparing(CartItem::getBookId));

        List<Long> bookIds = lines.stream().map(CartItem::getBookId).toList();
        Map<Long, BookInfo> bookMap = bookLookupService.findByIds(bookIds);

        // 5. Atomically decrement stock in ascending bookId order
        for (CartItem line : lines) {
            BookInfo book = bookMap.get(line.getBookId());
            if (book == null || !book.active()) {
                throw new ApiException(ErrorCode.BOOK_UNAVAILABLE, "A book in the cart is no longer available.");
            }
            int updated = jdbc.update("""
                    UPDATE books
                    SET stock_quantity = stock_quantity - ?, version = version + 1
                    WHERE id = ? AND active = true AND stock_quantity >= ?
                    """, line.getQuantity(), line.getBookId(), line.getQuantity());

            if (updated == 0) {
                // Determine exact reason
                BookInfo latest = bookLookupService.findById(line.getBookId()).orElse(null);
                if (latest == null || !latest.active()) {
                    throw new ApiException(ErrorCode.BOOK_UNAVAILABLE, "A book in the cart is no longer available.");
                } else {
                    throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                            "Not enough stock is available for the requested quantity.");
                }
            }
        }

        // 6. Calculate pricing
        BigDecimal subtotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (CartItem line : lines) {
            BookInfo book = bookMap.get(line.getBookId());
            BigDecimal unitPrice = book.price().setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(lineTotal);
        }

        // 7. Loyalty calculation
        BigDecimal maxDiscount = subtotal.multiply(MAX_REDEEM_PERCENT).setScale(2, RoundingMode.DOWN);
        long maxPointsByCap = maxDiscount.divide(POINT_VALUE, 0, RoundingMode.DOWN).longValue();
        Long balance = jdbc.queryForObject("SELECT points_balance FROM users WHERE id = ?", Long.class, userId);
        long userPointsBalance = balance != null ? balance : 0L;
        long maxRedeemablePoints = Math.min(userPointsBalance, maxPointsByCap);

        long redeemPoints = request.safeRedeemPoints();
        if (redeemPoints > maxRedeemablePoints) {
            throw new ApiException(ErrorCode.POINTS_LIMIT_EXCEEDED,
                    "Requested points (" + redeemPoints + ") exceed maximum redeemable amount (" + maxRedeemablePoints + ").");
        }

        BigDecimal pointsDiscount = BigDecimal.valueOf(redeemPoints).multiply(POINT_VALUE)
                .setScale(2, RoundingMode.DOWN);
        BigDecimal total = subtotal.subtract(pointsDiscount).setScale(2, RoundingMode.HALF_UP);
        long pointsEarned = total.divideToIntegralValue(EARN_PER_AMOUNT).longValue();

        // 8. Generate order number and persist order
        String orderNumber = generateOrderNumber();
        Order order = Order.create(
                orderNumber,
                userId,
                subtotal,
                redeemPoints,
                pointsDiscount,
                total,
                pointsEarned,
                ShippingAddressEmbeddable.of(request.shippingAddress()),
                idempotencyKey,
                requestFingerprint);
        Order savedOrder = orderRepository.save(order);

        if (redeemPoints > 0) {
            int updated = jdbc.update("""
                    UPDATE users
                    SET points_balance = points_balance - ?
                    WHERE id = ? AND points_balance >= ?
                    """, redeemPoints, userId, redeemPoints);
            if (updated == 0) {
                throw new ApiException(ErrorCode.POINTS_LIMIT_EXCEEDED,
                        "Requested points exceed current balance.");
            }
            PointsTransaction tx = PointsTransaction.create(userId, savedOrder.getId(),
                    PointsTransactionType.REDEEM, -redeemPoints);
            pointsTransactionRepository.save(tx);
        }

        // 9. Persist order items
        List<OrderItem> savedItems = new ArrayList<>();
        for (CartItem line : lines) {
            BookInfo book = bookMap.get(line.getBookId());
            BigDecimal unitPrice = book.price().setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            OrderItem item = OrderItem.create(
                    savedOrder.getId(),
                    line.getBookId(),
                    book.isbn(),
                    book.title(),
                    unitPrice,
                    line.getQuantity(),
                    lineTotal);
            savedItems.add(orderItemRepository.save(item));
        }

        // 10. Persist order status history
        orderStatusHistoryRepository.save(OrderStatusHistory.create(
                savedOrder.getId(),
                null,
                OrderStatus.PLACED,
                userEmail != null ? userEmail : "SYSTEM",
                "Order placed via checkout"));

        // 11. Charge payment gateway (throws on decline / error, rolling back transaction)
        PaymentResult paymentResult = paymentGateway.charge(request.paymentToken(), total, orderNumber);
        paymentRepository.save(Payment.success(savedOrder.getId(), total, paymentResult.reference()));

        // 12. Clear cart
        cartItemRepository.deleteAll(lines);
        cart.touch();

        return new CheckoutResult(OrderResponse.of(savedOrder, savedItems), false);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderByNumber(Long userId, String orderNumber) {
        Order order = orderRepository.findByUserIdAndOrderNumber(userId, orderNumber)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Order not found."));
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
        List<OrderStatusHistory> history = orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(order.getId());
        return OrderResponse.of(order, items, history);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> getCustomerOrderHistory(Long userId, OrderStatus status, int page, int size, String sortColumn, Sort.Direction direction) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortColumn));
        Page<Order> orderPage = (status == null)
                ? orderRepository.findByUserId(userId, pageable)
                : orderRepository.findByUserIdAndStatus(userId, status, pageable);
        
        Page<OrderResponse> responsePage = orderPage.map(order -> {
            List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
            List<OrderStatusHistory> history = orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(order.getId());
            return OrderResponse.of(order, items, history);
        });
        return PageResponse.of(responsePage);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> getAdminOrders(OrderStatus status, int page, int size, String sortColumn, Sort.Direction direction) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortColumn));
        Page<Order> orderPage = (status == null)
                ? orderRepository.findAll(pageable)
                : orderRepository.findByStatus(status, pageable);
        
        Page<OrderResponse> responsePage = orderPage.map(order -> {
            List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
            List<OrderStatusHistory> history = orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(order.getId());
            return OrderResponse.of(order, items, history);
        });
        return PageResponse.of(responsePage);
    }

    @Transactional(readOnly = true)
    public OrderResponse getAdminOrder(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Order not found."));
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
        List<OrderStatusHistory> history = orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(order.getId());
        return OrderResponse.of(order, items, history);
    }

    @Transactional
    public OrderResponse cancelCustomerOrder(Long userId, String userEmail, String orderNumber) {
        Order order = orderRepository.findByOrderNumberForUpdate(orderNumber)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Order not found."));
        
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Order not found.");
        }
        
        if (order.getStatus() != OrderStatus.PLACED) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Order cannot be cancelled in status: " + order.getStatus());
        }
        
        performCancel(order, order.getStatus(), userEmail != null ? userEmail : "CUSTOMER", "Cancelled by customer");
        
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
        List<OrderStatusHistory> history = orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(order.getId());
        return OrderResponse.of(order, items, history);
    }

    @Transactional
    public OrderResponse updateAdminOrderStatus(String userEmail, String orderNumber, OrderStatusUpdateRequest request) {
        Order order = orderRepository.findByOrderNumberForUpdate(orderNumber)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Order not found."));
        
        OrderStatus oldStatus = order.getStatus();
        OrderStatus newStatus = request.status();
        
        if (newStatus == OrderStatus.CANCELLED) {
            if (!oldStatus.canTransitionTo(OrderStatus.CANCELLED)) {
                throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION,
                        "Cannot transition order status from " + oldStatus + " to CANCELLED");
            }
            performCancel(order, oldStatus, userEmail != null ? userEmail : "ADMIN", request.reason() != null ? request.reason() : "Cancelled by admin");
        } else {
            if (!oldStatus.canTransitionTo(newStatus)) {
                throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION,
                        "Cannot transition order status from " + oldStatus + " to " + newStatus);
            }
            
            order.changeStatus(newStatus);
            orderRepository.save(order);
            
            if (newStatus == OrderStatus.DELIVERED) {
                creditEarnedPoints(order);
            }
            
            orderStatusHistoryRepository.save(OrderStatusHistory.create(
                    order.getId(),
                    oldStatus,
                    newStatus,
                    userEmail != null ? userEmail : "ADMIN",
                    request.reason() != null ? request.reason() : "Status updated by admin"));
        }
        
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
        List<OrderStatusHistory> history = orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(order.getId());
        return OrderResponse.of(order, items, history);
    }

    private void performCancel(Order order, OrderStatus oldStatus, String changedBy, String reason) {
        order.changeStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        
        // 1. Restore stock
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
        for (OrderItem item : items) {
            jdbc.update("""
                    UPDATE books
                    SET stock_quantity = stock_quantity + ?, version = version + 1
                    WHERE id = ?
                    """, item.getQuantity(), item.getBookId());
        }
        
        // 2. Simulate payment refund
        paymentRepository.findByOrderId(order.getId()).ifPresent(payment -> {
            payment.refund();
            paymentRepository.save(payment);
        });
        
        // 3. Persist order status history
        orderStatusHistoryRepository.save(OrderStatusHistory.create(
                order.getId(),
                oldStatus,
                OrderStatus.CANCELLED,
                changedBy,
                reason));

        // 4. Restore redeemed points
        restoreRedeemedPoints(order);
    }

    private void creditEarnedPoints(Order order) {
        Boolean alreadyEarned = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM points_transactions WHERE order_id = ? AND type = 'EARN')",
                Boolean.class, order.getId());
        if (Boolean.FALSE.equals(alreadyEarned) && order.getPointsEarned() > 0) {
            jdbc.update("UPDATE users SET points_balance = points_balance + ? WHERE id = ?",
                    order.getPointsEarned(), order.getUserId());
            
            PointsTransaction tx = PointsTransaction.create(order.getUserId(), order.getId(),
                    PointsTransactionType.EARN, order.getPointsEarned());
            pointsTransactionRepository.save(tx);
        }
    }

    private void restoreRedeemedPoints(Order order) {
        Boolean alreadyRestored = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM points_transactions WHERE order_id = ? AND type = 'RESTORE')",
                Boolean.class, order.getId());
        if (Boolean.FALSE.equals(alreadyRestored) && order.getPointsRedeemed() > 0) {
            jdbc.update("UPDATE users SET points_balance = points_balance + ? WHERE id = ?",
                    order.getPointsRedeemed(), order.getUserId());
            
            PointsTransaction tx = PointsTransaction.create(order.getUserId(), order.getId(),
                    PointsTransactionType.RESTORE, order.getPointsRedeemed());
            pointsTransactionRepository.save(tx);
        }
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || !key.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST,
                    "Idempotency-Key header is required and must be 1-64 alphanumeric characters, underscores or hyphens.");
        }
    }

    private static long computeAdvisoryLockKey(Long userId, String idempotencyKey) {
        long userHash = userId == null ? 0L : userId;
        long keyHash = idempotencyKey.hashCode() & 0xFFFFFFFFL;
        return (CHECKOUT_LOCK_NAMESPACE << 32) ^ (userHash << 16) ^ keyHash;
    }

    private static String computeFingerprint(CheckoutRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = request.shippingAddress().fullName() + "|"
                    + request.shippingAddress().addressLine1() + "|"
                    + request.shippingAddress().postalCode() + "|"
                    + request.safeRedeemPoints() + "|"
                    + request.paymentToken();
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing", e);
        }
    }

    private String generateOrderNumber() {
        String datePart = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sb = new StringBuilder("BW-").append(datePart).append("-");
            for (int i = 0; i < 6; i++) {
                sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
            }
            String candidate = sb.toString();
            if (!orderRepository.existsByOrderNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate unique order number");
    }
}
