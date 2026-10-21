package com.plinko.bo.game;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import com.plinko.bo.odds.MegaBoard;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import org.springframework.stereotype.Component;

/**
 * The last check before a win is paid, on every path that pays (the live round and every retry): the round, its bet
 * and its credit are read back from the database and the win is worked out again from the stored balls. Nothing else
 * may call {@code pam.credit}. A round that fails is never paid; the caller blocks it as TAMPERED.
 * <ol>
 * <li>C1: the round has an outcome and its signature verifies;</li>
 * <li>C2: it belongs to the player, in the player's currency;</li>
 * <li>C3: exactly one debit, accepted by the wallet, for exactly the round's total bet;</li>
 * <li>C4: the total bet is the bet times the balls;</li>
 * <li>C5: the win worked out again from the stored balls is exactly the round's win; each multiplier is the prize of
 *     its slot in the odds version the round was played on, times the boost of the gates its path goes
 *     through; every path ends in its ball's slot, every wheel spin belongs to a ball in a SPIN slot;</li>
 * <li>C6: the credit is the round's own (signed by the game for this round, player and amount) and pays its win;</li>
 * <li>C7: exactly one credit, and no rollback;</li>
 * <li>C8: the win is within the hard limit per round in force when the round was played;</li>
 * <li>C9: the round is waiting to be paid (DEBITED live, CREDIT_PENDING on a retry).</li>
 * </ol>
 */
@Component
public class WinCheck {

    private final RoundSigner signer;
    private final WalletSigner walletSigner;
    private final OddsService odds;

    public WinCheck(RoundSigner signer, WalletSigner walletSigner, OddsService odds, PlinkoProperties properties) {
        this.signer = signer;
        this.walletSigner = walletSigner;
        this.odds = odds;
    }

    /** Null if the round may be paid now; otherwise why not (for the audit log: names the check and the amounts). */
    public String problem(GameRepository.Round round, List<GameRepository.Transaction> transactions, GameRepository.Player player) {
        if (!RoundStatus.DEBITED.equals(round.status()) && !RoundStatus.CREDIT_PENDING.equals(round.status())) {
            return "C9: status " + round.status() + " is not waiting to be paid";
        }
        if (round.playerId() != player.id()) {
            return "C2: round of player " + round.playerId() + ", paying player " + player.id();
        }
        if (!round.currency().equals(player.currency())) {
            return "C2: round in " + round.currency() + ", player in " + player.currency();
        }
        String outcome = outcomeProblem(round);
        if (outcome != null) {
            return outcome;
        }
        List<GameRepository.Transaction> debits = ofType(transactions, "DEBIT");
        if (debits.size() != 1) {
            return "C3: " + debits.size() + " debits";
        }
        GameRepository.Transaction debit = debits.get(0);
        if (!"OK".equals(debit.status())) {
            return "C3: debit " + debit.status();
        }
        if (debit.amount().compareTo(round.totalBet()) != 0) {
            return "C3: debit " + debit.amount() + ", total bet " + round.totalBet();
        }
        String debitProblem = walletSigner.debitProblem(debit, round, player);
        if (debitProblem != null) {
            return "C3: " + debitProblem;
        }
        if (!ofType(transactions, "ROLLBACK").isEmpty()) {
            return "C7: the round has a rollback";
        }
        List<GameRepository.Transaction> credits = ofType(transactions, "CREDIT");
        if (credits.size() != 1) {
            return "C7: " + credits.size() + " credits";
        }
        GameRepository.Transaction credit = credits.get(0);
        if (credit.amount().compareTo(round.totalWin()) != 0) {
            return "C6: credit " + credit.amount() + ", round win " + round.totalWin();
        }
        // verifyCredit accepts the round's credit id under any round key: a credit begun before a key rotation keeps its id.
        if (!walletSigner.verifyCredit(credit, round, player)) {
            // R6: not tampering as such: genuine money signed before the cutoff, which the retired key no longer authorises
            if (walletSigner.signedWithRetiredKey(credit)) {
                return RETIRED_KEY + " (LEGACY_KEY_UNTIL " + walletSigner.legacyUntil().map(Object::toString).orElse("?")
                        + "): the original key no longer authorises a payment; settle it with the casino";
            }
            return "C6: the credit is not the round's own signed credit";
        }
        return null;
    }

    /** R6: the start of the problem reported for a credit signed with the original key once that key is retired. */
    public static final String RETIRED_KEY = "C6: the credit is signed with a retired key";

    /** The start of the problem reported when the odds version a round was played on can't be read or verified. */
    static final String ODDS_UNREADABLE = "C5: odds version ";

    /**
     * N14.5: true if the problem is that the round's odds version can't be read or verified, which points at the setup
     * (a key mistake, a missing row) rather than at this round: its credit is left waiting, not blocked as tampered.
     */
    public static boolean oddsUnreadable(String problem) {
        return problem != null && problem.startsWith(ODDS_UNREADABLE);
    }

    /**
     * The checks that need only the round itself (C1, C4, C5, C8); also run by the hourly scan. Null if they pass.
     */
    public String outcomeProblem(GameRepository.Round round) {
        if (round.signature() == null || round.totalWin() == null || !signer.verify(round)) {
            return "C1: the round's outcome is missing or its signature does not verify";
        }
        RoundWin.Outcome outcome;
        try {
            outcome = RoundWin.parse(round.slots(), round.multipliers(), round.bonus());
        } catch (RuntimeException e) {
            return "C5: the stored outcome is malformed (" + e.getMessage() + ")";
        }
        // a round bought with a power-up costs bet x balls x its price (rounded up to the cent)
        BigDecimal cost = outcome.power() == null ? round.bet().multiply(BigDecimal.valueOf(round.balls()))
                : com.plinko.bo.bonus.BonusService.powerCost(round.bet(), round.balls(), outcome.powerPrice());
        if (round.totalBet().compareTo(cost) != 0) {
            return "C4: total bet " + round.totalBet() + " is not " + round.bet() + " x " + round.balls()
                    + (outcome.power() == null ? "" : " x " + outcome.powerPrice());
        }
        if (outcome.power() != null && (!com.plinko.bo.bonus.BonusService.POWERS.contains(outcome.power())
                || outcome.powerPrice().compareTo(BigDecimal.ONE) <= 0)) {
            return "C5: unknown power-up " + outcome.power() + " or price " + outcome.powerPrice();
        }
        if (outcome.slots().size() != round.balls()) {
            return "C5: " + outcome.slots().size() + " balls stored, " + round.balls() + " bet";
        }
        String balls = ballsProblem(round, outcome);
        if (balls != null) {
            return balls;
        }
        // N14.4: a round from before limits were stored with it (max_win NULL) is not held to today's limit, which may
        // have been lowered since: its cap is only checked to be below what the balls add up to
        BigDecimal limit = round.maxWin() == null ? null : round.maxWin().setScale(2, RoundingMode.DOWN);
        BigDecimal raw = outcome.raw(round.bet());
        if (limit != null) {
            // what the round should have paid, worked out the way it was played (RoundWin.paid)
            RoundWin.Paid expected = RoundWin.paid(raw, limit);
            if (!java.util.Objects.equals(outcome.capped() == null ? null : outcome.capped().stripTrailingZeros(),
                    expected.capped() == null ? null : expected.capped().stripTrailingZeros())) {
                return outcome.capped() == null ? "C8: win " + raw + " is above the limit per round of " + limit + " but was not capped"
                        : "C5: capped at " + outcome.capped() + " but the balls add up to " + raw + " (limit " + limit + ")";
            }
            if (round.totalWin().compareTo(expected.total()) != 0) {
                return expected.capped() != null ? "C5: capped win " + round.totalWin() + " is not the limit " + limit + " below " + raw
                        : "C5: win " + round.totalWin() + ", the balls add up to " + raw;
            }
            return null;
        }
        // no limit stored with the round: a cap must still be what the balls added up to, and the win below it
        if (outcome.capped() != null) {
            if (outcome.capped().compareTo(raw) != 0) {
                return "C5: capped at " + outcome.capped() + " but the balls add up to " + raw;
            }
            if (round.totalWin().compareTo(raw) >= 0) {
                return "C5: capped win " + round.totalWin() + " is not below " + raw;
            }
        } else if (round.totalWin().compareTo(raw) != 0) {
            return "C5: win " + round.totalWin() + ", the balls add up to " + raw;
        }
        return null;
    }

    /** Every slot on the board, every multiplier the prize of its slot in the round's odds version times its gates. */
    private String ballsProblem(GameRepository.Round round, RoundWin.Outcome outcome) {
        Optional<List<BigDecimal>> prizes = round.oddsRef() == null ? Optional.empty()
                : odds.playedOn(round.oddsRef(), round.rows(), round.risk());
        if (round.oddsRef() != null && prizes.isEmpty()) {
            Optional<String> unreadable = odds.unreadable(round.oddsRef());
            if (unreadable.isPresent()) {
                return ODDS_UNREADABLE + round.oddsRef() + " " + unreadable.get();
            }
        }
        if (round.oddsRef() != null && (prizes.isEmpty() || prizes.get().size() != PlinkoBoard.prizeCount(round.rows()))) {
            return "C5: the round's odds version " + round.oddsRef() + " was changed or is not for " + round.rows() + " rows, risk " + round.risk();
        }
        String mega = megaProblem(round, outcome);
        if (mega != null) {
            return mega;
        }
        for (int ball = 0; ball < outcome.slots().size(); ball++) {
            BigDecimal gates = BigDecimal.ONE;
            if (outcome.gateBoost() != null) {
                gates = outcome.gateBoost().pow(MegaBoard.gatesPassed(MegaBoard.layouts(round.rows()).get(outcome.layout()), outcome.paths().get(ball)));
            }
            String problem = ballProblem(round, "ball " + ball, outcome.slots().get(ball), outcome.multipliers().get(ball),
                    gates.multiply(outcome.megaBallTimes(ball)), prizes);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /**
     * Paths to the balls' own slots, a gate boost above 1, MEGA BALLs on real balls (once each, above 1x), wheel spins
     * on SPIN slots only, at most 3 MEGA SPINs.
     */
    private static String megaProblem(GameRepository.Round round, RoundWin.Outcome outcome) {
        for (int ball = 0; ball < outcome.slots().size(); ball++) {
            if (outcome.slots().get(ball) < 0 || outcome.slots().get(ball) > round.rows()) {
                return "C5: ball " + ball + " landed in slot " + outcome.slots().get(ball) + ", outside a board of " + round.rows() + " rows";
            }
        }
        for (int ball = 0; ball < outcome.paths().size(); ball++) {
            int path = outcome.paths().get(ball);
            if (!MegaBoard.isPath(round.rows(), path) || MegaBoard.slotOf(path) != outcome.slots().get(ball)) {
                return "C5: ball " + ball + "'s path " + path + " does not end in its slot " + outcome.slots().get(ball);
            }
        }
        if (outcome.layout() < 0 || outcome.layout() >= MegaBoard.layouts(round.rows()).size()) {
            return "C5: gate layout " + outcome.layout() + " does not exist on a board of " + round.rows() + " rows";
        }
        if (outcome.gateBoost() != null && outcome.gateBoost().compareTo(BigDecimal.ONE) <= 0) {
            return "C5: gates boost " + outcome.gateBoost() + " is not above 1";
        }
        // heavy shells: every ball is a MEGA BALL; cluster shells: every ball has its two decoys, each on the board
        if ("heavy".equals(outcome.power()) && outcome.megaBalls().size() != outcome.slots().size()) {
            return "C5: heavy shells, but " + outcome.megaBalls().size() + " of " + outcome.slots().size() + " balls were MEGA BALLs";
        }
        if ("cluster".equals(outcome.power()) != !outcome.decoys().isEmpty()
                || ("cluster".equals(outcome.power()) && outcome.decoys().size() != outcome.slots().size())) {
            return "C5: cluster decoys do not match the balls";
        }
        for (List<Integer> pair : outcome.decoys()) {
            for (int slot : pair) {
                if (slot < 0 || slot > round.rows()) {
                    return "C5: a decoy landed in slot " + slot + ", outside a board of " + round.rows() + " rows";
                }
            }
        }
        java.util.Set<Integer> megaBalls = new java.util.HashSet<>();
        for (RoundWin.MegaBall megaBall : outcome.megaBalls()) {
            if (megaBall.ball() < 0 || megaBall.ball() >= outcome.slots().size() || !megaBalls.add(megaBall.ball())) {
                return "C5: MEGA BALL " + megaBall.ball() + " does not exist or is repeated";
            }
            if (megaBall.multiplier().compareTo(BigDecimal.ONE) <= 0) {
                return "C5: MEGA BALL " + megaBall.ball() + " multiplies by " + megaBall.multiplier() + ", not above 1";
            }
        }
        java.util.Set<Integer> spun = new java.util.HashSet<>();
        for (RoundWin.Spin spin : outcome.spins()) {
            if (spin.ball() < 0 || spin.ball() >= outcome.slots().size() || !spun.add(spin.ball())) {
                return "C5: wheel spin of ball " + spin.ball() + " does not exist or is repeated";
            }
            if (!MegaBoard.isSpinSlot(round.rows(), outcome.slots().get(spin.ball()))) {
                return "C5: ball " + spin.ball() + " spun the wheel from slot " + outcome.slots().get(spin.ball()) + ", not a SPIN slot";
            }
            if (spin.segment() < 0 || spin.multiplier().signum() <= 0) {
                return "C5: wheel spin of ball " + spin.ball() + " has segment " + spin.segment() + " and pays " + spin.multiplier();
            }
        }
        if (outcome.megaSpins().size() > 3) {
            return "C5: " + outcome.megaSpins().size() + " MEGA SPINs in one round";
        }
        for (RoundWin.MegaSpin mega : outcome.megaSpins()) {
            if (mega.stake().signum() < 0 || mega.segment() < 0 || mega.multiplier().signum() <= 0) {
                return "C5: MEGA SPIN for " + mega.stake() + " has segment " + mega.segment() + " and pays " + mega.multiplier();
            }
        }
        return null;
    }

    private static String ballProblem(GameRepository.Round round, String name, int slot, BigDecimal multiplier, BigDecimal boost,
                                      Optional<List<BigDecimal>> prizes) {
        if (slot < 0 || slot > round.rows()) {
            return "C5: " + name + " landed in slot " + slot + ", outside a board of " + round.rows() + " rows";
        }
        if (multiplier.signum() < 0 || boost.signum() <= 0) {
            return "C5: " + name + " has multiplier " + multiplier + " and boost " + boost;
        }
        if (prizes.isPresent()) {
            BigDecimal expected = prizes.get().get(PlinkoBoard.prizeIndexOfSlot(round.rows(), slot)).multiply(boost);
            if (expected.compareTo(multiplier) != 0) {
                return "C5: " + name + " in slot " + slot + " paid " + multiplier + "x, the odds it was played on give " + expected.stripTrailingZeros() + "x";
            }
        }
        return null;
    }

    private static List<GameRepository.Transaction> ofType(List<GameRepository.Transaction> transactions, String type) {
        return transactions.stream().filter(tx -> type.equals(tx.type())).toList();
    }
}
