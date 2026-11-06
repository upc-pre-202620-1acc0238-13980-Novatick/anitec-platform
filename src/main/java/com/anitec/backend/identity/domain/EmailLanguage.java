package com.anitec.backend.identity.domain;

import java.util.List;
import java.util.Locale;

/** Per-operation email preference, not a persisted account preference or HTTP response locale. */
public enum EmailLanguage {
    ES("es"), EN("en");

    private final String tag;
    EmailLanguage(String tag) { this.tag = tag; }
    public String tag() { return tag; }

    public static EmailLanguage fromAcceptLanguage(String header) {
        if (header == null || header.isBlank() || header.length() > 512) return ES;
        try {
            String selected = Locale.lookupTag(Locale.LanguageRange.parse(header), List.of("es", "en"));
            return "en".equals(selected) ? EN : ES;
        } catch (IllegalArgumentException invalid) {
            // Header values need not appear in diagnostics; unsupported/malformed input uses the default.
            return ES;
        }
    }
}
