package com.anitec.backend.livestock.infrastructure;

import org.springframework.data.domain.Sort;

/** Shared Spring Data sorts for livestock repositories. */
final class SortHelper {

    private SortHelper() {
    }

    static Sort byCreatedAt() {
        return Sort.by(Sort.Direction.DESC, "createdAt");
    }
}
