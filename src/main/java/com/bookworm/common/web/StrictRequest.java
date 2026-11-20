package com.bookworm.common.web;

/**
 * Marker for request bodies that must not contain unknown JSON properties. Boot's Jackson 3 mapper ignores
 * unknown properties and {@code @JsonIgnoreProperties(ignoreUnknown = false)} does not override that for
 * records, so {@link StrictJsonConfig} installs a handler that rejects them for types implementing this
 * interface only. Phase 2 request records do not implement it and keep ignoring unknown fields.
 */
public interface StrictRequest {
}
