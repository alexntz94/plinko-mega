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
 * The draws happen in a fixed order (each ball's slot, path, MEGA BALL and wheel spin, then each MEGA SPIN), and the
 * stored text is written by {@link RoundWin.Text}, the same class that reads it back.
 * <p>
 * The slot is drawn from the odds table, then a path to it; the gates the path goes through multiply the prize, and a
 * MEGA BALL multiplies it again. A ball in a SPIN slot also spins the wheel, which pays that ball's win again times the
 * segment, and a full meter pays its pot as a MEGA SPIN of the gold wheel.
 */
final class RoundDraw {

    private RoundDraw() {
    }

    /** Where the randomness comes from: the odds service and the bonus service in the game, fixed values in tests. */
    interface Dice {
        int slot(OddsTable table);

        /** The landing slot, falling to the left with this chance (a magnetic field); plain dice ignore it. */
        default int slot(OddsTable table, double leftChance) {
            return slot(table);
        }

        /** A path to this slot (every path to it equally likely). */
        int path(int rows, int slot);

        /** A path to this slot that falls straight through these holes (see MegaBoard), every such path equally likely. */
        default int path(int rows, int slot, java.util.Set<Integer> holes) {
            return path(rows, slot);
        }

        /** Whether a ball hits an ammo crate: the index of its multiplier in BonusService.CRATES, or -1. */
        default int crate() {
            return -1;
        }

        /** A payout after the balls (a tank target, the meltdown): the index of what it pays in its table. */
        default int target(List<BonusSettings.Segment> table) {
            return 0;
        }

        /** Which of the board's gate layouts this round uses (0..count-1, every one equally likely). */
        default int layout(int count) {
            return 0;
        }

        /** The index of the segment a spin of this wheel stops on. */
        int segment(BonusSettings.Wheel wheel);

        /** Whether a ball is a MEGA BALL: the index of its multiplier, or -1 for a plain ball. */
        default int megaBall(BonusSettings.MegaBall megaBall) {
            return -1;
        }
    }

    /** Ball {@code ball} dropped as a MEGA BALL, which multiplied its win by {@code multiplier}. */
    record MegaBall(int ball, BigDecimal multiplier) {
    }

    /** A wheel spin started by ball {@code ball}, as the game shows it. */
    record Spin(int ball, int segment, BigDecimal multiplier, BigDecimal win) {
    }

    /** A MEGA SPIN of the gold wheel, as the game shows it. */
    record MegaSpin(BigDecimal stake, int segment, BigDecimal multiplier, BigDecimal win) {
    }

    /** The meter before the round: charges, pot value, and the state it continues from (null: the meter is off). */
    record MeterBefore(int charges, BigDecimal pot, String prev) {
    }

    /** Everything decided, as the game shows it and as it is stored and signed. */
    record Result(List<Integer> slots, List<BigDecimal> multipliers, List<BigDecimal> wins, int chargesAfter, BigDecimal potAfter, String meterPrev, BigDecimal totalWin,
                  String slotText, String multiplierText, String bonusText,
                  List<Integer> paths, List<Integer> gates, List<Spin> spins, List<MegaSpin> megaSpins, List<MegaBall> megaBalls,
                  int layout, List<List<Integer>> decoys, List<MegaBall> crates, List<BigDecimal> targets, List<BigDecimal> targetWins) {
    }

    /** A power-up bought for the round (heavy or cluster) and what a ball cost with it (x the bet), or none. */
    record Power(String name, BigDecimal price) {
    }

    static Result draw(OddsTable table, BonusSettings bonusSettings, BigDecimal bet, int balls, MeterBefore meterBefore,
                       BigDecimal maxWin, Dice dice) {
        return draw(table, bonusSettings, bet, balls, meterBefore, maxWin, dice, null);
    }

    /**
     * power: heavy makes every ball a MEGA BALL; cluster lands every ball three times (each slot drawn from the table)
     * and keeps the landing with the best prize, the other two are decoys (drawn for the game to show, paying nothing).
     */
    static Result draw(OddsTable table, BonusSettings bonusSettings, BigDecimal bet, int balls, MeterBefore meterBefore,
                       BigDecimal maxWin, Dice dice, Power power) {
        boolean heavy = power != null && "heavy".equals(power.name());
        boolean cluster = power != null && "cluster".equals(power.name());
        List<List<Integer>> decoys = new ArrayList<>();
        Integer[] rank = cluster ? clusterRank(table) : null;
        BonusSettings.Meter meterSettings = bonusSettings.meterOn() ? bonusSettings.meter() : null;
        int charges = meterBefore == null ? 0 : meterBefore.charges();
        BigDecimal pot = meterBefore == null ? BigDecimal.ZERO : meterBefore.pot();
        RoundWin.Text text = new RoundWin.Text();
        BigDecimal gateBoost = bonusSettings.gatesOn() ? bonusSettings.gates().boost() : null;
        // this round's gate layout (the gates move every round)
        List<List<MegaBoard.Gate>> layouts = MegaBoard.layouts(table.rows());
        int layout = gateBoost == null ? 0 : dice.layout(layouts.size());
        if (gateBoost != null) {
            text.gates(gateBoost);
            text.layout(layout);
        }
        if (power != null) {
            text.power(power.name(), power.price());
        }
        // pegs smashed by this round's MEGA BALLs so far: the balls after one fall straight through the holes
        java.util.Set<Integer> holes = new java.util.HashSet<>();
        List<Integer> paths = new ArrayList<>();
        List<Integer> gates = new ArrayList<>();
        List<Spin> spins = new ArrayList<>();
        List<MegaBall> megaBalls = new ArrayList<>();
        List<MegaBall> crates = new ArrayList<>();
        List<BigDecimal> prizeList = table.prizes().stream().map(OddsTable.Prize::multiplier).toList();
        List<Integer> slots = new ArrayList<>();
        List<BigDecimal> multipliers = new ArrayList<>();
        BigDecimal totalWin = BigDecimal.ZERO;
        // what each ball paid, exactly as added up into the win (the game shows these, never its own sums)
        List<BigDecimal> wins = new ArrayList<>();
        for (int ball = 0; ball < balls; ball++) {
            int slot = dice.slot(table, BonusService.leftChance(power == null ? null : power.name()));
            if (cluster) {
                // three landings; the best prize is the ball's, the others are decoys
                int[] landed = {slot, dice.slot(table), dice.slot(table)};
                int best = 0;
                for (int k = 1; k < 3; k++) {
                    if (rank[PlinkoBoard.prizeIndexOfSlot(table.rows(), landed[k])] > rank[PlinkoBoard.prizeIndexOfSlot(table.rows(), landed[best])]) {
                        best = k;
                    }
                }
                slot = landed[best];
                List<Integer> others = new ArrayList<>();
                for (int k = 0; k < 3; k++) {
                    if (k != best) {
                        others.add(landed[k]);
                    }
                }
                decoys.add(List.copyOf(others));
                text.decoys(ball, others);
            }
            // chain lightning: the slot's prize and half of each neighbour's
            BigDecimal prize = BonusService.landingPrize(power == null ? null : power.name(), table.rows(), prizeList, slot);
            int path = holes.isEmpty() ? dice.path(table.rows(), slot) : dice.path(table.rows(), slot, holes);
            int passed = gateBoost == null ? 0 : MegaBoard.gatesPassed(layouts.get(layout), path);
            BigDecimal multiplier = prize.multiply(gateBoost == null ? BigDecimal.ONE : gateBoost.pow(passed));
            // a power-up by slot (airstrike, missile, night) multiplies the prize where the ball landed
            if (power != null) {
                multiplier = multiplier.multiply(BonusService.slotFactor(power.name(), table.rows(), table.risk(), slot));
            }
            // chain reaction: an ammo crate on the ball's way multiplies its win
            if (power != null && "crates".equals(power.name())) {
                int crate = dice.crate();
                if (crate >= 0) {
                    BigDecimal times = BonusService.CRATES.get(crate).multiplier().stripTrailingZeros();
                    multiplier = multiplier.multiply(times);
                    crates.add(new MegaBall(ball, times));
                    text.crate(ball, times);
                }
            }
            paths.add(path);
            gates.add(passed);
            text.path(path);
            // a MEGA BALL multiplies what its ball wins
            // heavy shells: every ball is one (its multiplier still drawn by the weights)
            int mega = heavy ? dice.megaBall(new BonusSettings.MegaBall(true, OddsTable.HUNDRED, bonusSettings.megaBall().multipliers()))
                    : bonusSettings.megaBallOn() ? dice.megaBall(bonusSettings.megaBall()) : -1;
            if (mega >= 0) {
                BigDecimal times = bonusSettings.megaBall().multipliers().get(mega).multiplier().stripTrailingZeros();
                multiplier = multiplier.multiply(times);
                megaBalls.add(new MegaBall(ball, times));
                text.megaBall(ball, times);
                holes.addAll(MegaBoard.smashed(table.rows(), path));
            }
            // a ball on a big prize charges the meter and puts its share of the bet into the pot
            if (meterSettings != null && BonusService.chargesMeter(bonusSettings, table.rows(), table.risk(),
                    PlinkoBoard.prizeIndexOfSlot(table.rows(), slot))) {
                charges++;
                pot = pot.add(BonusService.potCharge(bonusSettings, bet));
            }
            slots.add(slot);
            multipliers.add(multiplier.stripTrailingZeros());
            BigDecimal win = RoundWin.ball(bet, multiplier);
            wins.add(win);
            totalWin = totalWin.add(win);
            // a SPIN slot also spins the wheel, which multiplies what the ball won
            if (bonusSettings.wheelOn() && MegaBoard.isSpinSlot(table.rows(), slot)) {
                int segment = dice.segment(bonusSettings.wheel());
                BigDecimal pays = bonusSettings.wheel().segments().get(segment).multiplier();
                BigDecimal spinWin = RoundWin.spin(bet, multiplier, pays);
                spins.add(new Spin(ball, segment, pays.stripTrailingZeros(), spinWin));
                text.spin(ball, segment, pays);
                totalWin = totalWin.add(spinWin);
            }
        }
        // tank assault, core meltdown: after the balls, payouts of x the round's plain bet (5 tank targets, 1 meltdown)
        List<BigDecimal> targets = new ArrayList<>();
        List<BigDecimal> targetWins = new ArrayList<>();
        int afterBalls = BonusService.targetCount(power == null ? null : power.name());
        if (afterBalls > 0) {
            List<BonusSettings.Segment> payouts = BonusService.targetTable(power.name());
            BigDecimal stake = bet.multiply(BigDecimal.valueOf(balls));
            for (int t = 0; t < afterBalls; t++) {
                BigDecimal pays = payouts.get(dice.target(payouts)).multiplier().stripTrailingZeros();
                BigDecimal win = RoundWin.ball(stake, pays);
                targets.add(pays);
                targetWins.add(win);
                totalWin = totalWin.add(win);
            }
            text.targets(power.name(), targets);
        }
        // a full meter pays its pot as a MEGA SPIN of the gold wheel, after the balls (at most 3 a round; further
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
        return new Result(slots, multipliers, wins, charges, pot, meterPrev, totalWin,
                slots.stream().map(String::valueOf).collect(Collectors.joining(",")),
                multipliers.stream().map(BigDecimal::toPlainString).collect(Collectors.joining(",")), text.text(),
                paths, gates, spins, megaSpins, megaBalls, layout, List.copyOf(decoys), List.copyOf(crates), List.copyOf(targets),
                List.copyOf(targetWins));
    }

    /** Each prize position's rank for a cluster shell (higher is better): by prize, then by position. */
    static Integer[] clusterRank(OddsTable table) {
        Integer[] order = BonusService.clusterOrder(table);
        Integer[] rank = new Integer[order.length];
        for (int k = 0; k < order.length; k++) {
            rank[order[k]] = k;
        }
        return rank;
    }
}
