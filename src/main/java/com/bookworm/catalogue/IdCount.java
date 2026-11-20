package com.bookworm.catalogue;

/** A row id with a count, for grouped {@code bookCount} queries (avoids N+1). */
record IdCount(Long id, long count) {
}
