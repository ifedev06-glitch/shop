package com.example.shopsecure.util;

import com.example.shopsecure.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class Pagination {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private Pagination() {
    }

    public static PageRequest newestFirst(int page, int size) {
        if (page < 0) {
            throw new BusinessException("Page must be zero or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException("Page size must be between 1 and " + MAX_SIZE);
        }
        return PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );
    }
}
