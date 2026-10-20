package com.plinko.bo.game;

import java.math.BigDecimal;

import com.plinko.common.Crypto;
import com.plinko.config.PlinkoProperties;
import com.plinko.repo.GameRepository;
import org.springframework.stereotype.Component;

/**
 * HMAC over everything that decides a payout. The key lives only in the environment, so a round
 * whose bet, outcome or win was changed in the database no longer verifies and is never paid.
 */
@Component
public class RoundSigner {

    private final com.plinko.common.KeyRing keys;

    public RoundSigner(PlinkoProperties properties) {
        this(new com.plinko.common.KeyRing(properties));
    }

    /** Signs with the current round key and verifies with the key a signature names (review 7.2). */
    @org.springframework.beans.factory.annotation.Autowired
    public RoundSigner(com.plinko.common.KeyRing keys) {
        this.keys = keys;
    }

    /**
     * The bonus part (golden balls and the like) is appended only when the round had one, and the odds version and
     * win limit only when they were recorded, so rounds signed before either existed still verify exactly as they did.
     */
    public String sign(String roundId, long playerId, int rows, int risk, BigDecimal bet, int balls, BigDecimal totalBet,
                       BigDecimal totalWin, String currency, String slots, String multipliers, String bonus, Long oddsRef,
                       BigDecimal maxWin) {
        String data = String.join("|", roundId, Long.toString(playerId), Integer.toString(rows), Integer.toString(risk),
                plain(bet), Integer.toString(balls), plain(totalBet), plain(totalWin), currency, slots, multipliers);
        if (bonus != null) {
            data += "|bonus=" + bonus;
        }
        if (oddsRef != null) {
            data += "|odds=" + oddsRef;
        }
        if (maxWin != null) {
            data += "|limit=" + plain(maxWin);
        }
        return keys.sign(com.plinko.common.KeyRing.Use.ROUNDS, data);
    }

    private static String data(GameRepository.Round r) {
        String data = String.join("|", r.id(), Long.toString(r.playerId()), Integer.toString(r.rows()), Integer.toString(r.risk()),
                plain(r.bet()), Integer.toString(r.balls()), plain(r.totalBet()), plain(r.totalWin()), r.currency(), r.slots(), r.multipliers());
        if (r.bonus() != null) {
            data += "|bonus=" + r.bonus();
        }
        if (r.oddsRef() != null) {
            data += "|odds=" + r.oddsRef();
        }
        if (r.maxWin() != null) {
            data += "|limit=" + plain(r.maxWin());
        }
        return data;
    }

    public String sign(String roundId, long playerId, int rows, int risk, BigDecimal bet, int balls, BigDecimal totalBet,
                       BigDecimal totalWin, String currency, String slots, String multipliers, String bonus) {
        return sign(roundId, playerId, rows, risk, bet, balls, totalBet, totalWin, currency, slots, multipliers, bonus, null, null);
    }

    public String sign(String roundId, long playerId, int rows, int risk, BigDecimal bet, int balls, BigDecimal totalBet,
                       BigDecimal totalWin, String currency, String slots, String multipliers) {
        return sign(roundId, playerId, rows, risk, bet, balls, totalBet, totalWin, currency, slots, multipliers, null);
    }

    public boolean verify(GameRepository.Round round) {
        if (round.signature() == null || round.totalWin() == null) {
            return false;
        }
        if (keys.verify(com.plinko.common.KeyRing.Use.ROUNDS, data(round), round.signature(), round.createdAt())) {
            return true;
        }
        // N8 #2: a round first signed with the original key, signed again with a current key by the re-signing job
        return resigned != null && com.plinko.common.KeyRing.isLegacy(round.signature())
                && resigned.of(com.plinko.repo.ResignedSignatures.ROUNDS, round.id())
                        .filter(again -> !com.plinko.common.KeyRing.isLegacy(again))
                        .map(again -> keys.verify(com.plinko.common.KeyRing.Use.ROUNDS, data(round), again))
                        .orElse(false);
    }

    /** N8 #2: the round's signature made again with the current round key (only for a round that verifies now). */
    public String signAgain(GameRepository.Round round) {
        return keys.sign(com.plinko.common.KeyRing.Use.ROUNDS, data(round));
    }

    private com.plinko.repo.ResignedSignatures resigned;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setResigned(com.plinko.repo.ResignedSignatures resigned) {
        this.resigned = resigned;
    }

    private static String plain(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }
}
