package com.bugeon.fintechledger.common.web;

import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;

@Getter
public class PageResponse<T> {

    private final List<T> content;
    private final int     page;
    private final int     size;
    private final long    totalElements;
    private final int     totalPages;
    private final boolean last;

    private PageResponse(Page<T> source) {
        this.content       = source.getContent();
        this.page          = source.getNumber();
        this.size          = source.getSize();
        this.totalElements = source.getTotalElements();
        this.totalPages    = source.getTotalPages();
        this.last          = source.isLast();
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page);
    }
}
