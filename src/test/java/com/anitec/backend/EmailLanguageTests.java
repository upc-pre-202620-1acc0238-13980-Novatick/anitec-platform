package com.anitec.backend;

import com.anitec.backend.identity.domain.EmailLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class EmailLanguageTests {
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings={" ","es","es-PE","es-MX,en;q=0.1","fr,es;q=0.8,en;q=0.2","fr","*","invalid_header",
            "en;q=invalid","en;q=2","en;q=0,es;q=1","en;q=0,es;q=0"})
    void spanishIsCompatibleDefaultOrSelectedPreference(String value) {
        assertThat(EmailLanguage.fromAcceptLanguage(value)).isEqualTo(EmailLanguage.ES);
    }
    @ParameterizedTest @ValueSource(strings={"en","en-US","EN-gb","en-US,en;q=0.9,es;q=0.5",
            "es;q=0.3,en;q=0.9","fr-CA,en;q=0.8,es;q=0.5","es;q=0,en;q=1"})
    void selectsEnglishFromSupportedPreference(String value) {
        assertThat(EmailLanguage.fromAcceptLanguage(value)).isEqualTo(EmailLanguage.EN);
    }
    @Test void oversizedHeaderFallsBackWithoutParsingOrLogging() {
        assertThat(EmailLanguage.fromAcceptLanguage("en,".repeat(200))).isEqualTo(EmailLanguage.ES);
    }
}
