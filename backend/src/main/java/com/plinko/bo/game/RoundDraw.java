package com.plinko.bo.game;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.plinko.bo.bonus.BonusService;
import com.plinko.bo.odds.MegaBoard;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;

/**
 * Review 7.8: deciding a round's balls, apart from taking the bet and paying. Pure: every random draw comes from the
 * {@link Dice}, so a test can fix them and check the result, the win and the stored text exactly.
 * <p>
 * The draws happen in a fixed order (the hot slot, then each paid ball's slot, golden roll, path and wheel spin, then
 * each MEGA SPIN), and the stored text is written by {@link RoundWin.Text}, the same class that reads it back.
 * <p>
 * Plinko Mega: the slot is drawn from the odds table as always, then a path to it; the gates the path goes through
 * multiply the prize. A ball in a SPIN slot also spins the wheel for its bet, and a full meter pays its pot as a MEGA
 * SPIN of the gold wheel (instead of free balls).
 */
final class RoundDraw {

    private RoundDraw() {
    }

    /** Where the randomness comes from: the odds service and the bonus service in the game, fixed values in tests. */
    interface Dice {
        int hotSlot(BonusSettings bonus, int rows);

        int slot(OddsTable table);

        BigDecimal golden(BonusSettings bonus);

        /** A path to this slot (every path to it equally likely). */
        int path(int rows, int slot);

        /** The index of the segment a spin of this wheel stops on. */
        int segment(BonusSettings.Wheel wheel);
    }

    /** A wheel spin started by paid ball {@code ball}, as the game shows it. */
    record Spin(int ball, int segment, BigDecimal multiplier, BigDecimal win) {
    }

    /** A MEGA SPIN of the gold wheel, as the game shows it. */
    record MegaSpin(BigDecimal stake, int segment, BigDecimal multiplier, BigDecimal win) {
    }

    /** The meter before the round: charges, pot value, and the state it continues from (null: the meter is off). */
    record MeterBefore(int charges, BigDecimal pot, String prev) {
    }

    /** Everything decided, as the game shows it and as it is stored and signed. */
    record Result(List<Integer> slots, List<BigDecimal> multipliers, List<BigDecimal> boosts, List<BigDecimal> wins,
                  Integer hotSlot, BigDecimal hotBoost, List<Integer> freeSlots, List<BigDecimal> freeMultipliers,
                  List<BigDecimal> freeBoosts, List<BigDecimal> freeBets, List<BigDecimal> freeWins,
                  int chargesAfter, BigDecimal potAfter, String meterPrev, BigDecimal totalWin,
                  String slotText, String multiplierText, String bonusText,
                  List<Integer> paths, List<Integer> gates, List<Spin> spins, List<MegaSpin> megaSpins) {
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
        BigDecimal gateBoost = bonusSettings.gatesOn() ? bonusSettings.gates().boost() : null;
        if (gateBoost != null) {
            text.gates(gateBoost);
        }
        List<Integer> paths = new ArrayList<>();
        List<Integer> gates = new ArrayList<>();
        List<Spin> spins = new ArrayList<>();
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
            int path = dice.path(table.rows(), slot);
            int passed = gateBoost == null ? 0 : MegaBoard.gatesPassed(table.rows(), path);
            multiplier = multiplier.multiply(gateBoost == null ? BigDecimal.ONE : gateBoost.pow(passed));
            paths.add(path);
            gates.add(passed);
            text.path(path);
            // a paid ball on a big prize charges the meter and puts its share of the bet into the pot
            if (meterSettings != null && BonusService.chargesMeter(bonusSettings, table.rows(), table.risk(),
                    PlinkoBoard.prizeIndexOfSlot(table.rows(), slot))) {
                charges++;
                pot = pot.add(BonusService.potCharge(bonusSettings, bet));
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
            // a SPIN slot also spins the wheel, for the ball's bet
            if (bonusSettings.wheelOn() && MegaBoard.isSpinSlot(table.rows(), slot)) {
                int segment = dice.segment(bonusSettings.wheel());
                BigDecimal pays = bonusSettings.wheel().segments().get(segment).multiplier();
                BigDecimal spinWin = RoundWin.ball(bet, pays);
                spins.add(new Spin(ball, segment, pays.stripTrailingZeros(), spinWin));
                text.spin(ball, segment, pays);
                totalWin = totalWin.add(spinWin);
            }
        }
        // a full meter pays its pot as a MEGA SPIN of the gold wheel, after the paid balls (at most 3 a round; further
        // charges wait for the next round)
        List<MegaSpin> megaSpins = new ArrayList<>();
        for (int award = 0; meterSettings != null && charges >= meterSettings.charges() && award < 3; award++) {
            BigDecimal stake = BonusService.megaStake(bonusSettings, pot, charges);
            charges -= meterSettings.charges();
            pot = pot.subtract(stake).max(BigDecimal.ZERO);
            int segment = dice.segment(bonusSettings.megaWheel());
            BigDecimal pays = bonusSettings.megaWheel().segments().get(segment).multiplier();
            BigDecimal megaWin = RoundWin.ball(stake, pays);
            megaSpins.add(new MegaSpin(stake, segment, pays.stripTrailingZeros(), megaWin));
            text.mega(stake, segment, pays);
            totalWin = totalWin.add(megaWin);
        }
        List<Integer> freeSlots = List.of();
        List<BigDecimal> freeMultipliers = List.of();
        List<BigDecimal> freeBoosts = List.of();
        List<BigDecimal> freeBets = List.of();
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
                multipliers.stream().map(BigDecimal::toPlainString).collect(Collectors.joining(",")), text.text(),
                paths, gates, spins, megaSpins);
    }
}
