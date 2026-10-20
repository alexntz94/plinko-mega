package com.plinko.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Odds for one rows count and risk level, ordered by prize index (0 = centre). */
@JsonIgnoreProperties(value = {"rtp", "totalProbability", "maxMultiplier"}, allowGetters = true)
public record OddsTable(int rows, int risk, List<Prize> prizes) {

    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** probability is in percent, for landing on either slot of the prize position. */
    public record Prize(int index, BigDecimal multiplier, BigDecimal probability) {
    }

    /** Theoretical return to player, in percent. */
    public BigDecimal rtp() {
        return prizes.stream()
                .map(p -> p.multiplier().multiply(p.probability()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .round(MathContext.DECIMAL64);
    }

    public BigDecimal totalProbability() {
        return prizes.stream().map(Prize::probability).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal maxMultiplier() {
        return prizes.stream().map(Prize::multiplier).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }
}
