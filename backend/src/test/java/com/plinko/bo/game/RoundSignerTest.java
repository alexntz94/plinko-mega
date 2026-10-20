package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.plinko.TestFixtures;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.Test;

class RoundSignerTest {

    private final RoundSigner signer = new RoundSigner(TestFixtures.properties());

    private GameRepository.Round round(BigDecimal win, String slots, String signature) {
        return new GameRepository.Round("r1", 7, "s", 8, 2, new BigDecimal("1.00"), 3, new BigDecimal("3.00"), win, "EUR",
                RoundStatus.CREDIT_PENDING, slots, "0.2,29,0.3", null, signature, null, null);
    }

    @Test
    void detectsAnyChangeToTheOutcome() {
        String signature = signer.sign("r1", 7, 8, 2, new BigDecimal("1.00"), 3, new BigDecimal("3.00"), new BigDecimal("29.50"),
                "EUR", "4,0,3", "0.2,29,0.3");
        assertThat(signer.verify(round(new BigDecimal("29.50"), "4,0,3", signature))).isTrue();
        assertThat(signer.verify(round(new BigDecimal("2950.00"), "4,0,3", signature))).isFalse();
        assertThat(signer.verify(round(new BigDecimal("29.50"), "0,0,0", signature))).isFalse();
        assertThat(signer.verify(round(new BigDecimal("29.50"), "4,0,3", null))).isFalse();
    }

    @Test
    void theBonusIsSignedWithTheRoundAndOldRoundsStillVerify() {
        String withBonus = signer.sign("r1", 7, 8, 2, new BigDecimal("1.00"), 3, new BigDecimal("3.00"), new BigDecimal("29.50"),
                "EUR", "4,0,3", "0.2,29,0.3", "gold:1x3");
        String without = signer.sign("r1", 7, 8, 2, new BigDecimal("1.00"), 3, new BigDecimal("3.00"), new BigDecimal("29.50"),
                "EUR", "4,0,3", "0.2,29,0.3");
        GameRepository.Round golden = new GameRepository.Round("r1", 7, "s", 8, 2, new BigDecimal("1.00"), 3, new BigDecimal("3.00"),
                new BigDecimal("29.50"), "EUR", RoundStatus.COMPLETED, "4,0,3", "0.2,29,0.3", null, withBonus, null, null, "gold:1x3");
        assertThat(signer.verify(golden)).isTrue();
        // a bonus added, changed or removed afterwards no longer verifies
        assertThat(signer.verify(new GameRepository.Round("r1", 7, "s", 8, 2, new BigDecimal("1.00"), 3, new BigDecimal("3.00"),
                new BigDecimal("29.50"), "EUR", RoundStatus.COMPLETED, "4,0,3", "0.2,29,0.3", null, withBonus, null, null, "gold:1x10"))).isFalse();
        assertThat(signer.verify(new GameRepository.Round("r1", 7, "s", 8, 2, new BigDecimal("1.00"), 3, new BigDecimal("3.00"),
                new BigDecimal("29.50"), "EUR", RoundStatus.COMPLETED, "4,0,3", "0.2,29,0.3", null, without, null, null, "gold:1x3"))).isFalse();
        // a round without a bonus signs exactly as rounds did before bonuses existed
        assertThat(signer.verify(round(new BigDecimal("29.50"), "4,0,3", without))).isTrue();
        assertThat(withBonus).isNotEqualTo(without);
    }
}
