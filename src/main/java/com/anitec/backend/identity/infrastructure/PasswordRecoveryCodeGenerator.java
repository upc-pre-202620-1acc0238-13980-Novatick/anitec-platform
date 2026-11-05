package com.anitec.backend.identity.infrastructure;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.util.Locale;
@Component
public class PasswordRecoveryCodeGenerator implements com.anitec.backend.identity.application.PasswordRecoveryCodeSource {
    private final SecureRandom random = new SecureRandom();
    public String generate() { return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)); }
}
