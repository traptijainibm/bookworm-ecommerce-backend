package com.bookworm.wishlist;

import com.bookworm.catalogue.BookInfo;
import com.bookworm.catalogue.BookLookupService;
import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import com.bookworm.common.web.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wishlist use cases (Phase 4). The customer id always comes from the validated token.
 * <ul>
 *   <li>Add operations take a user-scoped advisory lock ({@code pg_advisory_xact_lock}) to prevent race
 *       conditions on the 200-item cap. Plain wishlist adds must NOT acquire the cart lock.</li>
 *   <li>Inactive books in the wishlist are rendered minimally with {@code UNAVAILABLE}; adding an inactive
 *       book returns 404 NOT_FOUND.</li>
 *   <li>Pagination supports {@code page >= 0}, {@code size 1..100}, and {@code sort} on {@code addedAt} only.</li>
 * </ul>
 */
@Service
public class WishlistService {

    public static final int MAX_ITEMS = 200;
    public static final long WISHLIST_LOCK_NAMESPACE = 0x57495348L; // 'WISH'

    private static final String BOOK_NOT_FOUND = "Book not found.";

    private final WishlistItemRepository items;
    private final BookLookupService books;

    public WishlistService(WishlistItemRepository items, BookLookupService books) {
        this.items = items;
        this.books = books;
    }

    public static long lockKey(Long userId) {
        return (WISHLIST_LOCK_NAMESPACE << 32) ^ (userId == null ? 0L : userId);
    }

    /**
     * Paginated wishlist for the customer.
     * {@code sort} accepts only 'addedAt', optionally followed by ',asc' or ',desc' (default: addedAt,desc).
     * Any other sort field or direction returns 400 VALIDATION_FAILED.
     */
    @Transactional(readOnly = true)
    public PageResponse<WishlistItemResponse> get(Long userId, int page, int size, String sort) {
        if (page < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Page index must not be negative.");
        }
        if (size < 1 || size > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Page size must be between 1 and 100.");
        }
        Sort sortObj = parseSort(sort);
        Page<WishlistItem> itemPage = items.findByUserId(userId, PageRequest.of(page, size, sortObj));

        List<Long> bookIds = itemPage.getContent().stream().map(WishlistItem::getBookId).toList();
        Map<Long, BookInfo> bookMap = books.findByIds(bookIds);

        List<WishlistItemResponse> content = itemPage.getContent().stream().map(item -> {
            BookInfo book = bookMap.get(item.getBookId());
            if (book == null || !book.active()) {
                return WishlistItemResponse.unavailable(item.getBookId(), book == null ? null : book.title());
            }
            return WishlistItemResponse.of(item, book);
        }).toList();

        return new PageResponse<>(content, itemPage.getNumber(), itemPage.getSize(),
                itemPage.getTotalElements(), itemPage.getTotalPages());
    }

    /**
     * Adds a book to the wishlist.
     * Takes only the user's wishlist advisory lock.
     * Unknown or inactive book -> 404 NOT_FOUND.
     * Already in wishlist -> returns existing item (created = false).
     * 200 items reached -> 422 WISHLIST_FULL.
     * Otherwise inserts and returns created item (created = true).
     */
    @Transactional
    public AddResult add(Long userId, Long bookId) {
        items.acquireAdvisoryLock(lockKey(userId));
        BookInfo book = books.findById(bookId).filter(BookInfo::active)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, BOOK_NOT_FOUND));

        Optional<WishlistItem> existing = items.findByUserIdAndBookId(userId, bookId);
        if (existing.isPresent()) {
            return new AddResult(WishlistItemResponse.of(existing.get(), book), false);
        }

        if (items.countByUserId(userId) >= MAX_ITEMS) {
            throw new ApiException(ErrorCode.WISHLIST_FULL, "The wishlist cannot hold more than 200 items.");
        }

        WishlistItem saved = items.save(WishlistItem.create(userId, bookId));
        return new AddResult(WishlistItemResponse.of(saved, book), true);
    }

    /** Idempotent remove: removing an absent item succeeds without error. Never examines book state. */
    @Transactional
    public void remove(Long userId, Long bookId) {
        items.deleteByUserIdAndBookId(userId, bookId);
    }

    public record AddResult(WishlistItemResponse item, boolean created) {
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "addedAt").and(Sort.by(Sort.Direction.DESC, "id"));
        }
        String trimmed = sort.trim();
        if (trimmed.equalsIgnoreCase("addedAt") || trimmed.equalsIgnoreCase("addedAt,desc")) {
            return Sort.by(Sort.Direction.DESC, "addedAt").and(Sort.by(Sort.Direction.DESC, "id"));
        }
        if (trimmed.equalsIgnoreCase("addedAt,asc")) {
            return Sort.by(Sort.Direction.ASC, "addedAt").and(Sort.by(Sort.Direction.ASC, "id"));
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "Invalid sort parameter. Only 'addedAt' (optionally with ',asc' or ',desc') is supported.");
    }
}
