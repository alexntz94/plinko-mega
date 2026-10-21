package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.plinko.TestFixtures;
import com.plinko.bo.odds.OddsService;
import com.plinko.common.Integrity;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The check before every payment (review item 1): genuine rounds of every kind pass, and every way of changing a
 * round, its bet or its credit is caught, naming the check that failed.
 */
class WinCheckTest {

    /** 8 rows: prize 0 is the centre slot 4, prize 4 the edge slots 0 and 8. */
    private static final List<BigDecimal> PRIZES = List.of(new BigDecimal("0.5"), new BigDecimal("1"), new BigDecimal("1.1"),
            new BigDecimal("2"), new BigDecimal("5.6"));
    private static final long ODDS = 5;

    private final RoundSigner signer = new RoundSigner(TestFixtures.properties());
    private final WalletSigner walletSigner = new WalletSigner(new Integrity(TestFixtures.properties()));
    private final OddsService odds = mock(OddsService.class);
    private final WinCheck check = new WinCheck(signer, walletSigner, odds, TestFixtures.properties());
    private final GameRepository.Player player = new GameRepository.Player(7, "pam-7", "alice", "EUR", null, false, Instant.now(), Instant.now());

    private BigDecimal bet = new BigDecimal("1.00");
    private int balls = 3;
    private BigDecimal totalBet = new BigDecimal("3.00");
    private BigDecimal maxWin = new BigDecimal("1000.00");
    private Long oddsRef = ODDS;
    private String status = RoundStatus.DEBITED;

    @BeforeEach
    void setUp() {
        when(odds.playedOn(ODDS, 8, 2)).thenReturn(Optional.of(PRIZES));
        when(odds.playedOn(anyLong(), anyInt(), anyInt())).thenAnswer(i -> (long) i.getArgument(0) == ODDS
                && (int) i.getArgument(1) == 8 && (int) i.getArgument(2) == 2 ? Optional.of(PRIZES) : Optional.empty());
    }

    /** A signed round as the game stores it. */
    private GameRepository.Round round(String slots, String multipliers, String plainBonus, String win) {
        BigDecimal totalWin = new BigDecimal(win);
        String bonus = TestPaths.with(slots, plainBonus);
        String signature = signer.sign("r1", 7, 8, 2, bet, balls, totalBet, totalWin, "EUR", slots, multipliers, bonus, oddsRef, maxWin);
        return new GameRepository.Round("r1", 7, "s", 8, 2, bet, balls, totalBet, totalWin, "EUR", status, slots, multipliers,
                null, signature, null, null, bonus, oddsRef, maxWin);
    }

    /** The round's debit (accepted) and its own signed credit. */
    private List<GameRepository.Transaction> paid(GameRepository.Round round) {
        List<GameRepository.Transaction> transactions = new ArrayList<>();
        transactions.add(new GameRepository.Transaction("debit-1", "r1", 7, "DEBIT", round.totalBet(), "OK", null, null, null, 1, null, null, null));
        transactions.add(credit(round, round.totalWin()));
        return transactions;
    }

    private GameRepository.Transaction credit(GameRepository.Round round, BigDecimal amount) {
        return new GameRepository.Transaction(walletSigner.creditId("r1"), "r1", 7, "CREDIT", amount, "PENDING", null, null, null, 0,
                null, null, walletSigner.signCredit("r1", "pam-7", amount, "EUR"));
    }

    private String problem(GameRepository.Round round) {
        return check.problem(round, paid(round), player);
    }

    // ---------------------------------------------------------------- genuine rounds pass

    @Test
    void aPlainRoundPasses() {
        // slots 4, 3, 0 -> prizes 0.5, 1, 5.6
        assertThat(problem(round("4,3,0", "0.5,1,5.6", null, "7.10"))).isNull();
    }

    @Test
    void aZeroWinPasses() {
        // 0.01 on the centre (0.5x) pays 0.005, rounded down to nothing
        bet = new BigDecimal("0.01");
        balls = 1;
        totalBet = new BigDecimal("0.01");
        assertThat(problem(round("4", "0.5", null, "0.00"))).isNull();
    }

    @Test
    void aMegaSpinPasses() {
        // the balls pay 7.10, a MEGA SPIN for 0.40 stops on x25: 10.00
        assertThat(problem(round("4,3,0", "0.5,1,5.6", "mega:0.40@6x25;meter:0/10:0.000000:start-7", "17.10"))).isNull();
    }

    @Test
    void aMegaBallPasses() {
        // the edge ball (5.6x) was a MEGA BALL x10: 56x
        assertThat(problem(round("4,3,0", "0.5,1,56", "megaball:2x10", "57.50"))).isNull();
    }

    @Test
    void aGateLayoutThatDoesNotExistIsCaught() {
        assertThat(problem(round("4,3,0", "0.5,1,5.6", "layout:999", "7.10"))).startsWith("C5: gate layout 999");
    }

    @Test
    void aMegaBallThatDoesNotMatchItsBallIsCaught() {
        // stored as x5, paid as x10
        assertThat(problem(round("4,3,0", "0.5,1,56", "megaball:2x5", "57.50"))).startsWith("C5: ball 2 in slot 0 paid 56x");
        // a MEGA BALL on a ball that does not exist, or twice on one ball
        assertThat(problem(round("4,3,0", "0.5,1,5.6", "megaball:3x2", "7.10"))).startsWith("C5: MEGA BALL 3");
        assertThat(problem(round("4,3,0", "1,1,5.6", "megaball:0x2,0x2", "7.60"))).startsWith("C5: MEGA BALL 0");
    }

    @Test
    void aCappedWinPasses() {
        maxWin = new BigDecimal("5.00");
        assertThat(problem(round("4,3,0", "0.5,1,5.6", "capped:7.10", "5.00"))).isNull();
    }

    @Test
    void anOldRoundWithoutBonusOddsOrLimitPasses() {
        oddsRef = null;
        maxWin = null;
        assertThat(problem(round("4,3,0", "0.5,1,5.6", null, "7.10"))).isNull();
    }

    @Test
    void anOldCappedRoundIsNotHeldToTodaysLimit() {
        // N14.4: played before limits were stored with rounds, capped at the limit of its time (5.00); today's is different
        maxWin = null;
        assertThat(problem(round("4,3,0", "0.5,1,5.6", "capped:7.10", "5.00"))).isNull();
        // a "cap" above what the balls add up to is still caught
        assertThat(problem(round("4,3,0", "0.5,1,5.6", "capped:7.10", "7.10"))).startsWith("C5");
    }

    @Test
    void aSignedDebitMustBeTheRoundsOwn() {
        // N3: the debit row is signed like the credit; one that doesn't verify (amount or round changed) is caught
        GameRepository.Round round = round("4,3,0", "0.5,1,5.6", null, "7.10");
        List<GameRepository.Transaction> transactions = paid(round);
        transactions.set(0, new GameRepository.Transaction("debit-1", "r1", 7, "DEBIT", round.totalBet(), "OK", null, null, null, 1, null, null,
                walletSigner.signDebit("debit-1", "r1", "pam-7", round.totalBet(), "EUR")));
        assertThat(check.problem(round, transactions, player)).isNull();
        transactions.set(0, new GameRepository.Transaction("debit-1", "r1", 7, "DEBIT", round.totalBet(), "OK", null, null, null, 1, null, null,
                walletSigner.signDebit("debit-1", "other-round", "pam-7", round.totalBet(), "EUR")));
        assertThat(check.problem(round, transactions, player)).startsWith("C3: the debit is not");
    }

    @Test
    void anUnsignedDebitPassesOnlyForARoundPlayedBeforeDebitsWereSigned() {
        // R9 #3: from the time this install records (job_state), every debit the game writes is signed
        com.plinko.repo.JobState state = mock(com.plinko.repo.JobState.class);
        WalletSigner dated = new WalletSigner(new Integrity(TestFixtures.properties()));
        dated.setJobState(state);
        Instant signedFrom = Instant.now().minus(java.time.Duration.ofHours(1));
        when(state.putIfAbsent(org.mockito.ArgumentMatchers.eq("debits.signed_from"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(signedFrom.toString());
        WinCheck datedCheck = new WinCheck(signer, dated, odds, TestFixtures.properties());
        GameRepository.Round signedRound = round("4,3,0", "0.5,1,5.6", null, "7.10");
        java.util.function.Function<Instant, GameRepository.Round> playedAt = at -> new GameRepository.Round(signedRound.id(), 7, "s", 8, 2, bet,
                balls, totalBet, signedRound.totalWin(), "EUR", status, signedRound.slots(), signedRound.multipliers(), null, signedRound.signature(),
                at, at, signedRound.bonus(), oddsRef, maxWin);
        GameRepository.Round before = playedAt.apply(signedFrom.minusSeconds(60));
        GameRepository.Round after = playedAt.apply(signedFrom.plusSeconds(60));
        assertThat(datedCheck.problem(before, paid(before), player)).as("from before debits were signed").isNull();
        assertThat(datedCheck.problem(after, paid(after), player)).startsWith("C3: the debit has no signature");
        assertThat(check.problem(after, paid(after), player)).as("without a recorded time (unit tests): as before").isNull();
    }

    @Test
    void aPendingCreditSignedWithTheOriginalKeyIsReportedAsSuchOnceTheCutoffPassesAtRuntime() throws InterruptedException {
        // Review R6: the key lists and a cutoff 1.5 s ahead; the round and its credit were signed with the original key
        Instant until = Instant.now().plusMillis(1500);
        com.plinko.common.KeyRing ring = new com.plinko.common.KeyRing(TestFixtures.properties(), "r1=" + "r".repeat(40), "d1=" + "d".repeat(40),
                "e1=" + "e".repeat(40), until.toString());
        WinCheck cutOff = new WinCheck(new RoundSigner(ring), new WalletSigner(new Integrity(ring)), odds, TestFixtures.properties());
        GameRepository.Round signed = round("4,3,0", "0.5,1,5.6", null, "7.10");
        Instant playedAt = Instant.now();
        GameRepository.Round round = new GameRepository.Round(signed.id(), 7, "s", 8, 2, bet, balls, totalBet, signed.totalWin(), "EUR",
                RoundStatus.CREDIT_PENDING, signed.slots(), signed.multipliers(), null, signed.signature(), playedAt, playedAt, signed.bonus(), oddsRef, maxWin);
        List<GameRepository.Transaction> owed = paid(round);
        assertThat(cutOff.problem(round, owed, player)).as("before the cutoff it is paid").isNull();
        Thread.sleep(Math.max(0, java.time.Duration.between(Instant.now(), until).toMillis()) + 200);
        assertThat(ring.legacyRetired()).isTrue();
        assertThat(cutOff.problem(round, owed, player)).startsWith(WinCheck.RETIRED_KEY).contains("LEGACY_KEY_UNTIL");
    }

    @Test
    void aRetryOfAPendingCreditPasses() {
        status = RoundStatus.CREDIT_PENDING;
        assertThat(problem(round("4,3,0", "0.5,1,5.6", null, "7.10"))).isNull();
    }

    @Test
    void aCreditBegunBeforeAKeyRotationIsStillPaid() {
        // N1: the credit row was written under the original key; the round key is then rotated to r1
        status = RoundStatus.CREDIT_PENDING;
        GameRepository.Round round = round("4,3,0", "0.5,1,5.6", null, "7.10");
        com.plinko.common.KeyRing rotated = new com.plinko.common.KeyRing(TestFixtures.properties(), "r1=" + "r".repeat(40), "", "");
        WalletSigner rotatedSigner = new WalletSigner(new Integrity(rotated));
        assertThat(rotatedSigner.creditId("r1")).isNotEqualTo(walletSigner.creditId("r1"));
        WinCheck afterRotation = new WinCheck(new RoundSigner(rotated), rotatedSigner, odds, TestFixtures.properties());
        assertThat(afterRotation.problem(round, paid(round), player)).isNull();
    }

    // ---------------------------------------------------------------- changed rounds are caught

    @Test
    void aChangedWinIsCaught() {
        // signed as 70.10: the signature is fine (someone with the key), but the balls add up to 7.10
        assertThat(problem(round("4,3,0", "0.5,1,5.6", null, "70.10"))).startsWith("C5: win 70.10");
    }

    @Test
    void aChangedMultiplierIsCaught() {
        // 56x in an edge slot whose prize is 5.6x
        assertThat(problem(round("4,3,0", "0.5,1,56", null, "57.50"))).startsWith("C5: ball 2 in slot 0 paid 56x");
    }

    @Test
    void aSlotOffTheBoardIsCaught() {
        assertThat(problem(round("4,3,9", "0.5,1,5.6", null, "7.10"))).contains("outside a board of 8 rows");
    }

    @Test
    void aMissingOrExtraBallIsCaught() {
        assertThat(problem(round("4,3", "0.5,1", null, "1.50"))).startsWith("C5: 2 balls stored, 3 bet");
        assertThat(problem(round("4,3,0,0", "0.5,1,5.6,5.6", null, "12.70"))).startsWith("C5: 4 balls stored, 3 bet");
    }

    @Test
    void aChangedMegaSpinStakeIsCaught() {
        // the stake comes from the meter's pot, which is not recomputed here: changed in the database, the signature fails
        GameRepository.Round round = round("4,3,0", "0.5,1,5.6", "mega:0.40@6x5", "9.10");
        GameRepository.Round edited = new GameRepository.Round(round.id(), 7, "s", 8, 2, bet, balls, totalBet, round.totalWin(), "EUR",
                status, round.slots(), round.multipliers(), null, round.signature(), null, null, TestPaths.with("4,3,0", "mega:40.00@6x5"),
                oddsRef, maxWin);
        assertThat(check.problem(edited, paid(edited), player)).startsWith("C1");
        // and even re-signed by someone holding the key, it never pays above the round's limit
        maxWin = new BigDecimal("100.00");
        assertThat(problem(round("4,3,0", "0.5,1,5.6", "mega:40.00@6x5", "207.10"))).startsWith("C8");
    }

    @Test
    void aCapThatDoesNotMatchIsCaught() {
        maxWin = new BigDecimal("5.00");
        assertThat(problem(round("4,3,0", "0.5,1,5.6", "capped:9.99", "5.00"))).startsWith("C5: capped at 9.99");
        assertThat(problem(round("4,3,0", "0.5,1,5.6", "capped:7.10", "6.00"))).startsWith("C5: capped win 6.00");
    }

    @Test
    void aWinAboveTheLimitOfItsTimeIsCaught() {
        maxWin = new BigDecimal("5.00");
        assertThat(problem(round("4,3,0", "0.5,1,5.6", null, "7.10"))).startsWith("C8: win 7.10");
    }

    @Test
    void aBadSignatureIsCaught() {
        GameRepository.Round round = round("4,3,0", "0.5,1,5.6", null, "7.10");
        GameRepository.Round edited = new GameRepository.Round(round.id(), 7, "s", 8, 2, bet, balls, totalBet, new BigDecimal("8.10"), "EUR",
                status, round.slots(), round.multipliers(), null, round.signature(), null, null, null, oddsRef, maxWin);
        assertThat(check.problem(edited, paid(edited), player)).startsWith("C1");
    }

    @Test
    void anOddsVersionThatIsMissingOrUnsignedIsCaught() {
        oddsRef = 99L;
        // changed in the database (signed with a key we hold, but not matching): tampering, the round is blocked
        assertThat(problem(round("4,3,0", "0.5,1,5.6", null, "7.10"))).startsWith("C5: the round's odds version 99 was changed");
        assertThat(WinCheck.oddsUnreadable(problem(round("4,3,0", "0.5,1,5.6", null, "7.10")))).isFalse();
        // N14.5: missing, or signed with a key this server doesn't hold: a setup problem, the credit waits
        when(odds.unreadable(99L)).thenReturn(Optional.of("is missing"));
        assertThat(problem(round("4,3,0", "0.5,1,5.6", null, "7.10"))).startsWith("C5: odds version 99 is missing");
        assertThat(WinCheck.oddsUnreadable(problem(round("4,3,0", "0.5,1,5.6", null, "7.10")))).isTrue();
    }

    @Test
    void malformedTextIsCaught() {
        assertThat(problem(round("4,3,x", "0.5,1,5.6", null, "7.10"))).startsWith("C5: the stored outcome is malformed");
        assertThat(problem(round("4,3,0", "0.5,1,5.6", "jackpot:1000", "7.10"))).startsWith("C5: the stored outcome is malformed");
        // a golden ball or a hot slot is not part of this game
        assertThat(problem(round("4,3,0", "0.5,1,11.2", "gold:2x2", "12.70"))).startsWith("C5: the stored outcome is malformed");
        assertThat(problem(round("4,3,0", "0.5,3,5.6", "hot:3x3", "9.10"))).startsWith("C5: the stored outcome is malformed");
    }

    @Test
    void aWrongTotalBetIsCaught() {
        totalBet = new BigDecimal("2.00");
        assertThat(problem(round("4,3,0", "0.5,1,5.6", null, "7.10"))).startsWith("C4");
    }

    // ---------------------------------------------------------------- the money around the round

    @Test
    void theBetMustHaveBeenTakenInFull() {
        GameRepository.Round round = round("4,3,0", "0.5,1,5.6", null, "7.10");
        assertThat(check.problem(round, List.of(credit(round, round.totalWin())), player)).isEqualTo("C3: 0 debits");
        List<GameRepository.Transaction> refused = paid(round);
        refused.set(0, new GameRepository.Transaction("debit-1", "r1", 7, "DEBIT", totalBet, "FAILED", null, null, null, 1, null, null, null));
        assertThat(check.problem(round, refused, player)).isEqualTo("C3: debit FAILED");
        List<GameRepository.Transaction> short_ = paid(round);
        short_.set(0, new GameRepository.Transaction("debit-1", "r1", 7, "DEBIT", new BigDecimal("0.30"), "OK", null, null, null, 1, null, null, null));
        assertThat(check.problem(round, short_, player)).startsWith("C3: debit 0.30");
    }

    @Test
    void theCreditMustBeTheRoundsOwnForItsWin() {
        GameRepository.Round round = round("4,3,0", "0.5,1,5.6", null, "7.10");
        List<GameRepository.Transaction> more = paid(round);
        more.set(1, credit(round, new BigDecimal("70.10")));
        assertThat(check.problem(round, more, player)).startsWith("C6: credit 70.10");
        List<GameRepository.Transaction> unsigned = paid(round);
        unsigned.set(1, new GameRepository.Transaction(walletSigner.creditId("r1"), "r1", 7, "CREDIT", round.totalWin(), "PENDING",
                null, null, null, 0, null, null, "forged"));
        assertThat(check.problem(round, unsigned, player)).startsWith("C6: the credit is not");
        List<GameRepository.Transaction> two = paid(round);
        two.add(credit(round, round.totalWin()));
        assertThat(check.problem(round, two, player)).isEqualTo("C7: 2 credits");
        List<GameRepository.Transaction> refunded = paid(round);
        refunded.add(new GameRepository.Transaction("rb", "r1", 7, "ROLLBACK", BigDecimal.ZERO, "OK", "debit-1", null, null, 1, null, null, null));
        assertThat(check.problem(round, refunded, player)).isEqualTo("C7: the round has a rollback");
    }

    @Test
    void theRoundMustBelongToThePlayerAndBeWaitingToBePaid() {
        GameRepository.Round round = round("4,3,0", "0.5,1,5.6", null, "7.10");
        GameRepository.Player other = new GameRepository.Player(8, "pam-8", "bob", "EUR", null, false, Instant.now(), Instant.now());
        assertThat(check.problem(round, paid(round), other)).startsWith("C2: round of player 7");
        status = RoundStatus.COMPLETED;
        assertThat(problem(round("4,3,0", "0.5,1,5.6", null, "7.10"))).startsWith("C9");
    }
}
