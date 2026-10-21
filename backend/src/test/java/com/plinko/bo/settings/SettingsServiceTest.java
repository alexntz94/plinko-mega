package com.plinko.bo.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.plinko.common.Crypto;
import org.junit.jupiter.api.Test;

class SettingsServiceTest {

    @Test
    void savedCopyReadsBackExactly() {
        Map<String, String> values = Map.of(
                "game.bonus", "{\"golden\":{\"chance\":8},\"note\":\"a=1;b:2\"}",
                "game.empty", "",
                "game.themes", "bombs-away",
                "game.ünïcode", "💥 5:x=y;");
        assertThat(SettingsService.parse(SettingsService.canonical(values))).isEqualTo(values);
        assertThat(SettingsService.parse(SettingsService.canonical(Map.of()))).isEmpty();
    }

    @Test
    void settlementLimitsAreWrittenCurrencyEqualsAmountAndStoredInOneForm() {
        assertThat(SettingsService.normalSettlementLimits(" GBP=900.50 , EUR=1000.00")).isEqualTo("EUR=1000,GBP=900.5");
        assertThat(SettingsService.settlementLimitProblems("EUR=1000,GBP=900", "EUR=100")).isEmpty();
        assertThat(SettingsService.settlementLimitProblems("", "EUR=100")).singleElement().asString().contains("at least one currency");
        assertThat(SettingsService.settlementLimitProblems("EUR:1000", "EUR=100")).singleElement().asString().contains("got 'EUR:1000'");
        assertThat(SettingsService.settlementLimitProblems("eur=1000", "EUR=100")).singleElement().asString().contains("got 'eur=1000'");
        assertThat(SettingsService.settlementLimitProblems("ABC=1000", "EUR=100")).singleElement().asString().contains("got 'ABC=1000'");
        assertThat(SettingsService.settlementLimitProblems("EUR=1000,", "EUR=100")).singleElement().asString().contains("got ''");
        assertThat(SettingsService.settlementLimitProblems("EUR=lots", "EUR=100")).singleElement().asString().contains("not a number");
        assertThat(SettingsService.settlementLimitProblems("EUR=0", "EUR=100")).singleElement().asString().contains("above 0");
        assertThat(SettingsService.settlementLimitProblems("EUR=10.005", "EUR=1")).singleElement().asString().contains("2 decimals");
        assertThat(SettingsService.settlementLimitProblems("EUR=10,EUR=20", "EUR=1")).singleElement().asString().contains("listed twice");
        assertThat(SettingsService.settlementLimitProblems("EUR=100", "EUR=200")).singleElement().asString()
                .contains("Other amounts: EUR 200 is above the highest settlement of 100");
        assertThat(SettingsService.settlementLimitProblems("x", "y")).hasSize(2);
    }

    @Test
    void fingerprintFormatIsUnchanged() {
        // the ledger of existing installs holds fingerprints in this format; changing it would stop their games
        assertThat(SettingsService.canonical(Map.of("b", "xy", "a", "1"))).isEqualTo("1:a=1:1;1:b=2:xy;");
        assertThat(Crypto.sha256Hex(SettingsService.canonical(Map.of("game.enabled", "true"))))
                .isEqualTo(Crypto.sha256Hex("12:game.enabled=4:true;"));
    }
}
