package com.bookworm.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookworm.cart.Cart;
import com.bookworm.loyalty.PointsTransactionRepository;
import com.bookworm.cart.CartItem;
import com.bookworm.cart.CartItemRepository;
import com.bookworm.cart.CartRepository;
import com.bookworm.catalogue.BookInfo;
import com.bookworm.catalogue.BookLookupService;
import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CheckoutServiceTest {

    private static final Long USER_ID = 8L;
    private static final String USER_EMAIL = "customer@example.com";
    private static final String IDEMPOTENCY_KEY = "key_123456";

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistoryRepository = mock(OrderStatusHistoryRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final CartRepository cartRepository = mock(CartRepository.class);
    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);
    private final BookLookupService bookLookupService = mock(BookLookupService.class);
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PointsTransactionRepository pointsTransactionRepository = mock(PointsTransactionRepository.class);

    private final CheckoutService service = new CheckoutService(
            orderRepository,
            orderItemRepository,
            orderStatusHistoryRepository,
            paymentRepository,
            cartRepository,
            cartItemRepository,
            bookLookupService,
            paymentGateway,
            jdbc,
            Clock.systemUTC(),
            pointsTransactionRepository);

    private final Cart cart = mock(Cart.class);
    private final List<CartItem> cartLines = new ArrayList<>();
    private final Map<Long, BookInfo> catalogue = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(pointsTransactionRepository.save(any(com.bookworm.loyalty.PointsTransaction.class)))
                .thenAnswer(i -> i.getArgument(0));
        
        // Mock points queries
        when(jdbc.queryForObject(eq("SELECT points_balance FROM users WHERE id = ?"), eq(Long.class), anyLong()))
                .thenReturn(0L);
        when(jdbc.queryForObject(eq("SELECT EXISTS(SELECT 1 FROM points_transactions WHERE order_id = ? AND type = 'EARN')"), eq(Boolean.class), anyLong()))
                .thenReturn(false);
        when(jdbc.queryForObject(eq("SELECT EXISTS(SELECT 1 FROM points_transactions WHERE order_id = ? AND type = 'RESTORE')"), eq(Boolean.class), anyLong()))
                .thenReturn(false);

        when(cart.getId()).thenReturn(1L);
        when(cartRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdOrderByIdAsc(1L)).thenAnswer(i -> new ArrayList<>(cartLines));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(i -> i.getArgument(0));
        when(orderStatusHistoryRepository.save(any(OrderStatusHistory.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentGateway.charge(anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(PaymentResult.success("sim_gw_123456"));
        when(bookLookupService.findByIds(any())).thenAnswer(i -> {
            Map<Long, BookInfo> res = new HashMap<>();
            for (Long id : i.<Iterable<Long>>getArgument(0)) {
                if (catalogue.containsKey(id)) {
                    res.put(id, catalogue.get(id));
                }
            }
            return res;
        });
        when(bookLookupService.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(catalogue.get(i.<Long>getArgument(0))));
        // Default successful JDBC stock updates
        when(jdbc.update(anyString(), anyInt(), anyLong(), anyInt())).thenReturn(1);
    }

    private static ShippingAddressDto sampleAddress() {
        return new ShippingAddressDto("John Doe", "9876543210", "123 Main St", "Apt 4", "Bangalore",
                "Karnataka", "560001", "India");
    }

    private static BookInfo sampleBook(Long id, String price, int stock, boolean active) {
        return new BookInfo(id, "978000000000" + id, "Book " + id,
                List.of(new BookInfo.AuthorRef(1L, "Author")),
                new BookInfo.CategoryRef(2L, "Category", "category"),
                new BigDecimal(price), "https://cover.jpg", active, stock);
    }

    @Test
    void checkoutRejectsMissingOrMalformedIdempotencyKey() {
        CheckoutRequest request = new CheckoutRequest(sampleAddress(), "tok_success", 0L);

        assertThatThrownBy(() -> service.checkout(USER_ID, USER_EMAIL, null, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.MALFORMED_REQUEST));

        assertThatThrownBy(() -> service.checkout(USER_ID, USER_EMAIL, "invalid key with spaces", request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.MALFORMED_REQUEST));
    }

    @Test
    void checkoutRejectsEmptyCart() {
        CheckoutRequest request = new CheckoutRequest(sampleAddress(), "tok_success", 0L);

        assertThatThrownBy(() -> service.checkout(USER_ID, USER_EMAIL, IDEMPOTENCY_KEY, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CART_EMPTY));
    }

    @Test
    void checkoutRejectsInactiveBookInCart() {
        catalogue.put(1L, sampleBook(1L, "100.00", 5, false));
        cartLines.add(CartItem.create(1L, 1L, 1));
        CheckoutRequest request = new CheckoutRequest(sampleAddress(), "tok_success", 0L);

        assertThatThrownBy(() -> service.checkout(USER_ID, USER_EMAIL, IDEMPOTENCY_KEY, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.BOOK_UNAVAILABLE));
    }

    @Test
    void checkoutRejectsInsufficientStock() {
        catalogue.put(1L, sampleBook(1L, "100.00", 1, true));
        cartLines.add(CartItem.create(1L, 1L, 5));
        // Atomic decrement returns 0 rows updated
        when(jdbc.update(anyString(), anyInt(), anyLong(), anyInt())).thenReturn(0);
        CheckoutRequest request = new CheckoutRequest(sampleAddress(), "tok_success", 0L);

        assertThatThrownBy(() -> service.checkout(USER_ID, USER_EMAIL, IDEMPOTENCY_KEY, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
    }

    @Test
    void checkoutCreatesOrderClearsCartAndChargesPayment() {
        catalogue.put(1L, sampleBook(1L, "200.00", 10, true));
        cartLines.add(CartItem.create(1L, 1L, 2)); // Subtotal = 400.00
        CheckoutRequest request = new CheckoutRequest(sampleAddress(), "tok_success", 0L);

        CheckoutService.CheckoutResult result = service.checkout(USER_ID, USER_EMAIL, IDEMPOTENCY_KEY, request);

        assertThat(result.isReplay()).isFalse();
        assertThat(result.order().subtotal()).isEqualByComparingTo("400.00");
        assertThat(result.order().total()).isEqualByComparingTo("400.00");
        assertThat(result.order().pointsEarned()).isEqualTo(4L);
        assertThat(result.order().status()).isEqualTo(OrderStatus.PLACED);

        verify(orderRepository).save(any(Order.class));
        verify(orderItemRepository).save(any(OrderItem.class));
        verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));
        verify(paymentGateway).charge(eq("tok_success"), eq(new BigDecimal("400.00")), anyString());
        verify(paymentRepository).save(any(Payment.class));
        verify(cartItemRepository).deleteAll(any());
        verify(cart).touch();
    }

    @Test
    void checkoutIdempotentReplayReturnsExistingOrder() {
        catalogue.put(1L, sampleBook(1L, "200.00", 10, true));
        cartLines.add(CartItem.create(1L, 1L, 1));
        CheckoutRequest request = new CheckoutRequest(sampleAddress(), "tok_success", 0L);

        // First successful checkout
        CheckoutService.CheckoutResult firstResult = service.checkout(USER_ID, USER_EMAIL, IDEMPOTENCY_KEY, request);

        // Mock existing order lookup with same fingerprint
        Order savedOrder = Order.create("ORD-123", USER_ID, new BigDecimal("200.00"), 0L, BigDecimal.ZERO,
                new BigDecimal("200.00"), 2L, ShippingAddressEmbeddable.of(sampleAddress()),
                IDEMPOTENCY_KEY, firstResult.order().shippingAddress().fullName() != null ? "fingerprint" : "other");

        // Set fingerprint via reflection or constructor
        when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(savedOrder));

        // When replay with different fingerprint -> 422
        assertThatThrownBy(() -> service.checkout(USER_ID, USER_EMAIL, IDEMPOTENCY_KEY, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void getOrderByNumberReturnsDetailsWithHistory() {
        Order order = Order.create("BW-123", USER_ID, new BigDecimal("100.00"), 0L, BigDecimal.ZERO,
                new BigDecimal("100.00"), 1L, ShippingAddressEmbeddable.of(sampleAddress()),
                IDEMPOTENCY_KEY, "fingerprint");
        setId(order, 123L);
        when(orderRepository.findByUserIdAndOrderNumber(USER_ID, "BW-123")).thenReturn(Optional.of(order));
        
        OrderItem item = OrderItem.create(123L, 1L, "978123", "Title", new BigDecimal("100.00"), 1, new BigDecimal("100.00"));
        when(orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())).thenReturn(List.of(item));
        
        OrderStatusHistory hist = OrderStatusHistory.create(order.getId(), null, OrderStatus.PLACED, "SYSTEM", "Placed");
        when(orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(order.getId())).thenReturn(List.of(hist));

        OrderResponse res = service.getOrderByNumber(USER_ID, "BW-123");
        assertThat(res.orderNumber()).isEqualTo("BW-123");
        assertThat(res.statusHistory()).hasSize(1);
        assertThat(res.statusHistory().get(0).toStatus()).isEqualTo(OrderStatus.PLACED);
    }

    @Test
    void getOrderByNumberThrowsNotFoundWhenAbsent() {
        when(orderRepository.findByUserIdAndOrderNumber(USER_ID, "BW-ABSENT")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOrderByNumber(USER_ID, "BW-ABSENT"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void cancelCustomerOrderSucceedsAndRestoresStockAndRefunds() {
        Order order = Order.create("BW-123", USER_ID, new BigDecimal("100.00"), 0L, BigDecimal.ZERO,
                new BigDecimal("100.00"), 1L, ShippingAddressEmbeddable.of(sampleAddress()),
                IDEMPOTENCY_KEY, "fingerprint");
        setId(order, 123L);
        when(orderRepository.findByOrderNumberForUpdate("BW-123")).thenReturn(Optional.of(order));
        
        OrderItem item = OrderItem.create(order.getId(), 5L, "978123", "Title", new BigDecimal("100.00"), 2, new BigDecimal("200.00"));
        when(orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())).thenReturn(List.of(item));
        
        Payment payment = Payment.success(order.getId(), new BigDecimal("100.00"), "ref-123");
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(Optional.of(payment));

        OrderResponse res = service.cancelCustomerOrder(USER_ID, USER_EMAIL, "BW-123");
        
        assertThat(res.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        
        verify(jdbc).update(anyString(), eq(2), eq(5L));
        verify(orderRepository).save(order);
        verify(paymentRepository).save(payment);
        verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));
    }

    @Test
    void cancelCustomerOrderRejectsWrongOwner() {
        Order order = Order.create("BW-123", 999L, new BigDecimal("100.00"), 0L, BigDecimal.ZERO,
                new BigDecimal("100.00"), 1L, ShippingAddressEmbeddable.of(sampleAddress()),
                IDEMPOTENCY_KEY, "fingerprint");
        setId(order, 123L);
        when(orderRepository.findByOrderNumberForUpdate("BW-123")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelCustomerOrder(USER_ID, USER_EMAIL, "BW-123"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void cancelCustomerOrderRejectsTerminalOrShipped() {
        Order order = Order.create("BW-123", USER_ID, new BigDecimal("100.00"), 0L, BigDecimal.ZERO,
                new BigDecimal("100.00"), 1L, ShippingAddressEmbeddable.of(sampleAddress()),
                IDEMPOTENCY_KEY, "fingerprint");
        order.changeStatus(OrderStatus.SHIPPED);
        setId(order, 123L);
        when(orderRepository.findByOrderNumberForUpdate("BW-123")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelCustomerOrder(USER_ID, USER_EMAIL, "BW-123"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
    }

    @Test
    void updateAdminOrderStatusSucceedsForValidTransitions() {
        Order order = Order.create("BW-123", USER_ID, new BigDecimal("100.00"), 0L, BigDecimal.ZERO,
                new BigDecimal("100.00"), 1L, ShippingAddressEmbeddable.of(sampleAddress()),
                IDEMPOTENCY_KEY, "fingerprint");
        setId(order, 123L);
        when(orderRepository.findByOrderNumberForUpdate("BW-123")).thenReturn(Optional.of(order));

        // PLACED -> PROCESSING
        OrderStatusUpdateRequest req1 = new OrderStatusUpdateRequest(OrderStatus.PROCESSING, "Processing start");
        OrderResponse res1 = service.updateAdminOrderStatus("admin@example.com", "BW-123", req1);
        assertThat(res1.status()).isEqualTo(OrderStatus.PROCESSING);
        
        // PROCESSING -> SHIPPED
        OrderStatusUpdateRequest req2 = new OrderStatusUpdateRequest(OrderStatus.SHIPPED, "Shipped");
        OrderResponse res2 = service.updateAdminOrderStatus("admin@example.com", "BW-123", req2);
        assertThat(res2.status()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void checkoutWithPointsRedemptionDeductsBalance() {
        catalogue.put(1L, sampleBook(1L, "1000.00", 10, true));
        cartLines.add(CartItem.create(1L, 1L, 1)); // subtotal 1000.00
        
        // Mock points balance to 500L
        when(jdbc.queryForObject(eq("SELECT points_balance FROM users WHERE id = ?"), eq(Long.class), eq(USER_ID)))
                .thenReturn(500L);
        when(jdbc.update(anyString(), eq(300L), eq(USER_ID), eq(300L))).thenReturn(1);
        
        CheckoutRequest request = new CheckoutRequest(sampleAddress(), "tok_success", 300L);
        CheckoutService.CheckoutResult result = service.checkout(USER_ID, USER_EMAIL, IDEMPOTENCY_KEY, request);
        
        assertThat(result.order().pointsRedeemed()).isEqualTo(300L);
        assertThat(result.order().pointsDiscount()).isEqualByComparingTo("30.00");
        assertThat(result.order().total()).isEqualByComparingTo("970.00");
        
        verify(pointsTransactionRepository).save(any(com.bookworm.loyalty.PointsTransaction.class));
    }

    @Test
    void deliveringOrderCreditsEarnedPoints() {
        Order order = Order.create("BW-123", USER_ID, new BigDecimal("100.00"), 0L, BigDecimal.ZERO,
                new BigDecimal("100.00"), 10L, ShippingAddressEmbeddable.of(sampleAddress()),
                IDEMPOTENCY_KEY, "fingerprint");
        setId(order, 123L);
        // Transition to SHIPPED first so we can transition to DELIVERED
        order.changeStatus(OrderStatus.SHIPPED);
        
        when(orderRepository.findByOrderNumberForUpdate("BW-123")).thenReturn(Optional.of(order));

        OrderStatusUpdateRequest req = new OrderStatusUpdateRequest(OrderStatus.DELIVERED, "Delivered successfully");
        service.updateAdminOrderStatus("admin@example.com", "BW-123", req);
        
        // Verify we set users points balance + 10 points
        verify(jdbc).update(eq("UPDATE users SET points_balance = points_balance + ? WHERE id = ?"), eq(10L), eq(USER_ID));
        verify(pointsTransactionRepository).save(any(com.bookworm.loyalty.PointsTransaction.class));
    }

    @Test
    void cancellingOrderRestoresRedeemedPoints() {
        Order order = Order.create("BW-123", USER_ID, new BigDecimal("100.00"), 50L, new BigDecimal("5.00"),
                new BigDecimal("95.00"), 0L, ShippingAddressEmbeddable.of(sampleAddress()),
                IDEMPOTENCY_KEY, "fingerprint");
        setId(order, 123L);
        when(orderRepository.findByOrderNumberForUpdate("BW-123")).thenReturn(Optional.of(order));
        
        OrderItem item = OrderItem.create(order.getId(), 1L, "978123", "Title", new BigDecimal("100.00"), 1, new BigDecimal("100.00"));
        when(orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())).thenReturn(List.of(item));

        service.cancelCustomerOrder(USER_ID, USER_EMAIL, "BW-123");
        
        // Verify points restored +50 points
        verify(jdbc).update(eq("UPDATE users SET points_balance = points_balance + ? WHERE id = ?"), eq(50L), eq(USER_ID));
        verify(pointsTransactionRepository).save(any(com.bookworm.loyalty.PointsTransaction.class));
    }

    private static void setId(Order order, Long id) {
        try {
            java.lang.reflect.Field field = Order.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(order, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
