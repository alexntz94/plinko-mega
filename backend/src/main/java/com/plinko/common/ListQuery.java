package com.plinko.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Paging and sorting of an admin list, read from the URL (?page=&size=&sort=&dir=). The sort key is only ever
 * one of the list's own whitelisted columns, so it can go into SQL safely.
 */
public record ListQuery(int page, int size, String sort, boolean ascending, String sortColumn) {

    public static final List<Integer> SIZES = List.of(25, 50, 100);

    /**
     * Review 3.10: lists count at most this many matches ("10,000+") and page no deeper (deep OFFSET pages read every
     * row before them); past that, the filters narrow the list.
     */
    public static final int COUNT_CAP = 10_000;

    /**
     * @param sortable sort key shown in the URL -> SQL column
     */
    public static ListQuery of(Integer page, Integer size, String sort, String dir, Map<String, String> sortable,
                               String defaultSort, boolean defaultAscending) {
        int safeSize = size != null && SIZES.contains(size) ? size : 50;
        int safePage = page == null || page < 1 ? 1 : Math.min(page, Math.max(1, COUNT_CAP / safeSize));
        String key = sort != null && sortable.containsKey(sort) ? sort : defaultSort;
        boolean asc = sort != null && sortable.containsKey(sort) ? "asc".equalsIgnoreCase(dir) : defaultAscending;
        return new ListQuery(safePage, safeSize, key, asc, sortable.get(key));
    }

    public int offset() {
        return (page - 1) * size;
    }

    /** ORDER BY clause from the whitelisted column, with a stable tie-breaker. */
    public String orderBy(String tieBreaker) {
        String direction = ascending ? "ASC" : "DESC";
        return " ORDER BY " + sortColumn + " " + direction + (tieBreaker == null ? "" : ", " + tieBreaker + " " + direction);
    }

    /** One page of results plus what the pager needs. */
    public record Page<T>(List<T> items, long total, ListQuery query) {

        public long from() {
            return total == 0 ? 0 : (long) query.offset() + 1;
        }

        public long to() {
            return Math.min(total, (long) query.offset() + items.size());
        }

        public int pages() {
            long counted = Math.min(total, COUNT_CAP);
            return (int) Math.max(1, (counted + query.size() - 1) / query.size());
        }

        /** True when there are more matches than are counted (shown as "10,000+"). */
        public boolean capped() {
            return total > COUNT_CAP;
        }

        public long shownTotal() {
            return Math.min(total, COUNT_CAP);
        }

        /** Page numbers to show, with null for a gap: 1 … 4 5 [6] 7 8 … 26 */
        public List<Integer> pageNumbers() {
            int last = pages();
            int current = query.page();
            List<Integer> numbers = new ArrayList<>();
            for (int p = 1; p <= last; p++) {
                if (p == 1 || p == last || Math.abs(p - current) <= 2) {
                    numbers.add(p);
                } else if (!numbers.isEmpty() && numbers.get(numbers.size() - 1) != null) {
                    numbers.add(null);
                }
            }
            return numbers;
        }
    }

    /** Builds a WHERE clause from optional filters; values always go in as bind parameters. */
    public static final class Where {
        private final StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        private final List<Object> params = new ArrayList<>();

        public Where add(boolean when, String clause, Object... values) {
            if (when) {
                sql.append(" AND ").append(clause);
                params.addAll(List.of(values));
            }
            return this;
        }

        public String sql() {
            return sql.toString();
        }

        public List<Object> params() {
            return params;
        }

        public static String like(String text) {
            return "%" + escape(text) + "%";
        }

        /** Starts with: an index on the column can be used (review 3.10: for ids). */
        public static String prefix(String text) {
            return escape(text.trim()) + "%";
        }

        private static String escape(String text) {
            return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        }

        public static boolean has(String value) {
            return value != null && !value.isBlank();
        }
    }
}
