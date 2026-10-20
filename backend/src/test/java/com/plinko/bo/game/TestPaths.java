package com.plinko.bo.game;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Stored outcomes written by hand in tests: every ball gets a path to its slot (right first, then left). */
final class TestPaths {

    private TestPaths() {
    }

    /** The bonus text with a path part for these slots added, unless it has one. */
    static String with(String slots, String bonus) {
        if (slots == null || slots.isEmpty() || (bonus != null && bonus.contains("path:"))) {
            return bonus;
        }
        String paths = "path:" + Arrays.stream(slots.split(",")).map(s -> {
            try {
                return String.valueOf((1 << Integer.parseInt(s.trim())) - 1);
            } catch (NumberFormatException e) {
                return "0";
            }
        }).collect(Collectors.joining(","));
        return bonus == null ? paths : paths + ";" + bonus;
    }
}
