package com.plinko.controller.admin;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.plinko.common.ListQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.util.UriComponentsBuilder;

/** Shared pieces of the admin lists: links that keep the current filters, CSV export, date parsing. */
public final class ListSupport {

    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private ListSupport() {
    }

    /** Links for a list page, each keeping every current filter (the page resets to 1 when sort or size change). */
    public record Links(String path, String query, ListQuery list) {

        static Links of(HttpServletRequest request, ListQuery list) {
            return new Links(request.getRequestURI(), request.getQueryString(), list);
        }

        private UriComponentsBuilder base() {
            return UriComponentsBuilder.fromPath(path).query(query);
        }

        public String page(int page) {
            return base().replaceQueryParam("page", page).build().encode().toUriString();
        }

        public String size(int size) {
            return base().replaceQueryParam("size", size).replaceQueryParam("page").build().encode().toUriString();
        }

        /** Sort by this column; clicking the current sort column flips its direction. */
        public String sort(String key) {
            boolean flip = key.equals(list.sort()) && !list.ascending();
            return base().replaceQueryParam("sort", key).replaceQueryParam("dir", flip ? "asc" : "desc")
                    .replaceQueryParam("page").build().encode().toUriString();
        }

        /** ▲ / ▼ next to the current sort column. */
        public String arrow(String key) {
            return key.equals(list.sort()) ? (list.ascending() ? "▲" : "▼") : "";
        }

        /** The same list as CSV, with the same filters and sort. */
        public String csv() {
            String csvPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            return UriComponentsBuilder.fromPath(csvPath + ".csv").query(query).replaceQueryParam("page").replaceQueryParam("size")
                    .build().encode().toUriString();
        }

        public boolean filtered() {
            return query != null && !query.replaceAll("(^|&)(page|size|sort|dir)=[^&]*", "").isBlank();
        }
    }

    static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Integer integer(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String time(Instant instant) {
        return instant == null ? "" : CSV_TIME.format(instant);
    }

    /** Writes a CSV download; every cell is quoted, and cells a spreadsheet would run as a formula are neutralised. */
    static void csv(HttpServletResponse response, String filename, List<String> header, List<List<Object>> rows) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        PrintWriter out = response.getWriter();
        out.print('﻿'); // Excel: read as UTF-8
        out.println(line(header.stream().map(h -> (Object) h).toList()));
        for (List<Object> row : rows) {
            out.println(line(row));
        }
        out.flush();
    }

    private static String line(List<Object> cells) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String text = cells.get(i) == null ? "" : cells.get(i).toString();
            if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 && !text.matches("-?\\d+(\\.\\d+)?")) {
                text = "'" + text;
            }
            out.append('"').append(text.replace("\"", "\"\"")).append('"');
        }
        return out.toString();
    }
}
