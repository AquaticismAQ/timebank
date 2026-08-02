package cn.timebank.common.web;

import org.springframework.data.domain.Page;

/** Contract §2.5 list envelope's {@code page} block. */
public record PageMeta(int number, int size, long totalItems, int totalPages) {

    public static PageMeta of(Page<?> page) {
        // page[number] is 1-based on the wire (contract §2.7); Spring Data Pageable is 0-based.
        return new PageMeta(page.getNumber() + 1, page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
