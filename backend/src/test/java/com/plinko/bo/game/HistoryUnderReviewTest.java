package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import com.plinko.model.BonusSettings;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.Test;

/** Review N14.12: the player's history shows a round whose stored outcome fails its check as UNDER_REVIEW, without its balls, win or prizes. */
class HistoryUnderReviewTest {

    private final TestGame game = new TestGame(BonusSettings.OFF);

    private Map<String, GameService.HistoryEntry> history() {
        return game.game.history("s", null, 20).rounds().stream().collect(Collectors.toMap(GameService.HistoryEntry::roundId, Function.identity()));
    }

    private void assertUnderReviewAfter(UnaryOperator<GameRepository.Round> edit) {
        String kept = game.play("1.00", 3).roundId();
        String edited = game.play("1.00", 3).roundId();
        game.repository.edit(edited, edit);

        Map<String, GameService.HistoryEntry> rounds = history();
        GameService.HistoryEntry review = rounds.get(edited);
        assertThat(review.status()).isEqualTo(GameService.UNDER_REVIEW);
        assertThat(review.round()).as("no replay").isNull();
        assertThat(review.prizes()).as("no prizes").isNull();
        assertThat(review.bet()).isEqualByComparingTo("1.00");
        assertThat(review.balls()).isEqualTo(3);

        GameService.HistoryEntry normal = rounds.get(kept);
        assertThat(normal.status()).isEqualTo(RoundStatus.COMPLETED);
        assertThat(normal.round()).isNotNull();
        assertThat(normal.round().slots()).hasSize(3);
        assertThat(normal.prizes()).isNotEmpty();
    }

    @Test
    void aRoundWhoseWinWasEditedIsUnderReview() {
        assertUnderReviewAfter(r -> new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(),
                r.totalBet(), r.totalWin().add(new BigDecimal("500")), r.currency(), r.status(), r.slots(), r.multipliers(), r.error(),
                r.signature(), r.createdAt(), r.updatedAt(), r.bonus(), r.oddsRef(), r.maxWin()));
    }

    @Test
    void aRoundWhoseBallsWereEditedIsUnderReview() {
        assertUnderReviewAfter(r -> new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(),
                r.totalBet(), r.totalWin(), r.currency(), r.status(), "0,0,0", r.multipliers(), r.error(), r.signature(), r.createdAt(),
                r.updatedAt(), r.bonus(), r.oddsRef(), r.maxWin()));
    }

    @Test
    void aRoundWhoseSignatureWasReplacedIsUnderReview() {
        assertUnderReviewAfter(r -> new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(),
                r.totalBet(), r.totalWin(), r.currency(), r.status(), r.slots(), r.multipliers(), r.error(), "0".repeat(64), r.createdAt(),
                r.updatedAt(), r.bonus(), r.oddsRef(), r.maxWin()));
    }

    @Test
    void aRoundBlockedWhilePlayedIsListedUnderReviewToo() {
        String round = game.play("1.00", 1).roundId();
        game.repository.edit(round, r -> new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(),
                r.totalBet(), r.totalWin().add(BigDecimal.ONE), r.currency(), RoundStatus.TAMPERED, r.slots(), r.multipliers(), r.error(),
                r.signature(), r.createdAt(), r.updatedAt(), r.bonus(), r.oddsRef(), r.maxWin()));
        assertThat(history().get(round).status()).isEqualTo(GameService.UNDER_REVIEW);
        assertThat(history().get(round).round()).isNull();
    }
}
