package com.plinko.bo.game;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.plinko.bo.bonus.BonusService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;

/**
 * Review 7.8: deciding a round's balls, apart from taking the bet and paying. Pure: every random draw comes from the
 * {@link Dice}, so a test can fix them and check the result, the win and the stored text exactly.
 * <p>
 * The draws happen in a fixed order (the hot slot, then each paid ball's slot and golden roll, then each free ball's),
 * and the stored text is written by {@link RoundWin.Text}, the same class that reads it back.
 */
final class RoundDraw {

    private RoundDraw() {
    }

    /** Where the randomness comes from: the odds service and the bonus service in the game, fixed values in tests. */
    interface Dice {
        int hotSlot(BonusSettings bonus, int rows);

        int slot(OddsTable table);

        BigDecimal golden(BonusSettings bonus);
    }

    /** The meter before the round: charges, pot value, and the state it continues from (null: the meter is off). */
    record MeterBefore(int charges, BigDecimal pot, String prev) {
    }

    /** Everything decided, as the game shows it and as it is stored and signed. */
    record Result(List<Integer> slots, List<BigDecimal> multipliers, List<BigDecimal> boosts, List<BigDecimal> wins,
                  Integer hotSlot, BigDecimal hotBoost, List<Integer> freeSlots, List<BigDecimal> freeMultipliers,
                  List<BigDecimal> freeBoosts, List<BigDecimal> freeBets, List<BigDecimal> freeWins,
                  int chargesAfter, BigDecimal potAfter, String meterPrev, BigDecimal totalWin,
                  String slotText, String multiplierText, String bonusText) {
    }

    static Result draw(OddsTable table, BonusSettings bonusSettings, BigDecimal bet, int balls, MeterBefore meterBefore,
                       BigDecimal maxWin, Dice dice) {
        // the hot slot of this round is drawn before any ball, from all slots alike
        int hotSlot = dice.hotSlot(bonusSettings, table.rows());
        BigDecimal hotBoost = hotSlot >= 0 ? bonusSettings.hot().boost() : null;
        BonusSettings.Meter meterSettings = bonusSettings.meterOn() ? bonusSettings.meter() : null;
        int charges = meterBefore == null ? 0 : meterBefore.charges();
        BigDecimal pot = meterBefore == null ? BigDecimal.ZERO : meterBefore.pot();
        RoundWin.Text text = new RoundWin.Text();
        if (hotSlot >= 0) {
            text.hot(hotSlot, hotBoost);
        }
        // the pot holds value, not money (see BonusService.potConversion): no gain from switching board
        List<Integer> slots = new ArrayList<>();
        List<BigDecimal> multipliers = new ArrayList<>();
        List<BigDecimal> boosts = new ArrayList<>();
        BigDecimal totalWin = BigDecimal.ZERO;
        // what each ball paid, exactly as added up into the win (the game shows these, never its own sums)
        List<BigDecimal> wins = new ArrayList<>();
        List<BigDecimal> freeWins = new ArrayList<>();
        for (int ball = 0; ball < balls; ball++) {
            int slot = dice.slot(table);
            BigDecimal boost = dice.golden(bonusSettings);
            BigDecimal prize = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(table.rows(), slot)).multiplier();
            BigDecimal multiplier = prize.multiply(boost);
            // a paid ball on a big prize charges the meter and puts its share of the bet into the pot
            if (meterSettings != null && BonusService.chargesMeter(bonusSettings, table.rows(), table.risk(),
                    PlinkoBoard.prizeIndexOfSlot(table.rows(), slot))) {
                charges++;
                pot = pot.add(BonusService.potCharge(bonusSettings, table, bet));
            }
            if (slot == hotSlot) {
                multiplier = multiplier.multiply(hotBoost);
            }
            slots.add(slot);
            multipliers.add(multiplier.stripTrailingZeros());
            boosts.add(boost.stripTrailingZeros());
            if (boost.compareTo(BigDecimal.ONE) > 0) {
                text.gold(ball, boost);
            }
            BigDecimal win = RoundWin.ball(bet, multiplier);
            wins.add(win);
            totalWin = totalWin.add(win);
        }
        // a full meter pays its pot as free balls, straight after the paid balls, on the same board (at most 3 times a
        // round; further charges wait for the next round). Free balls don't charge the meter.
        List<Integer> freeSlots = new ArrayList<>();
        List<BigDecimal> freeMultipliers = new ArrayList<>();
        List<BigDecimal> freeBoosts = new ArrayList<>();
        List<BigDecimal> freeBets = new ArrayList<>();
        for (int award = 0; meterSettings != null && charges >= meterSettings.charges() && award < 3; award++) {
            BigDecimal stake = BonusService.awardStake(bonusSettings, table, pot, charges);
            charges -= meterSettings.charges();
            pot = BonusService.potAfterPayout(bonusSettings, table, pot, stake);
            for (int ball = 0; ball < meterSettings.freeBalls(); ball++) {
                int slot = dice.slot(table);
                BigDecimal boost = dice.golden(bonusSettings);
                BigDecimal multiplier = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(table.rows(), slot)).multiplier().multiply(boost);
                if (slot == hotSlot) {
                    multiplier = multiplier.multiply(hotBoost);
                }
                freeSlots.add(slot);
                freeMultipliers.add(multiplier.stripTrailingZeros());
                freeBoosts.add(boost.stripTrailingZeros());
                freeBets.add(stake);
                text.free(stake, slot, multiplier, boost);
                BigDecimal win = RoundWin.ball(stake, multiplier);
                freeWins.add(win);
                totalWin = totalWin.add(win);
            }
        }
        // the meter state continues from exactly one earlier state (signed, and unique in the database)
        String meterPrev = meterSettings == null ? null : meterBefore.prev();
        if (meterSettings != null) {
            text.meter(charges, meterSettings.charges(), pot, meterPrev);
        }
        // the most a round can ever pay, bonuses included
        RoundWin.Paid paid = RoundWin.paid(totalWin, maxWin);
        if (paid.capped() != null) {
            text.capped(paid.capped());
        }
        totalWin = paid.total();
        return new Result(slots, multipliers, boosts, wins, hotSlot >= 0 ? hotSlot : null, hotBoost, freeSlots, freeMultipliers, freeBoosts,
                freeBets, freeWins, charges, pot, meterPrev, totalWin,
                slots.stream().map(String::valueOf).collect(Collectors.joining(",")),
                multipliers.stream().map(BigDecimal::toPlainString).collect(Collectors.joining(",")), text.text());
    }
}
