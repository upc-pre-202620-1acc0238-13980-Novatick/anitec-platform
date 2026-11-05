package com.anitec.backend;

import com.anitec.backend.identity.infrastructure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EmailCryptoSettingsTests {
 static String key(){byte[] b=new byte[32];new java.security.SecureRandom().nextBytes(b);return Base64.getEncoder().encodeToString(b);}
 MockEnvironment valid(){return new MockEnvironment().withProperty("email.delivery.mode","resend").withProperty("resend.api-key","synthetic-test-only").withProperty("resend.from","ANITEC <mail@notifications.vitalwatch.space>").withProperty("EMAIL_OUTBOX_ENCRYPTION_KEY",key());}
 @Test void disabledRequiresNoSecrets(){var s=new EmailDeliverySettings(new MockEnvironment());assertThat(s.enabled).isFalse();assertThatThrownBy(s::encryptionKey).isInstanceOf(IllegalStateException.class);}
 @ParameterizedTest @ValueSource(strings={"prod","test","dev","prod,test","prod,dev","prod,test,dev"})
 void profilesCannotBypassValidation(String p){var e=valid().withProperty("EMAIL_OUTBOX_ENCRYPTION_KEY","");e.setActiveProfiles(p.split(","));assertThatThrownBy(()->new EmailDeliverySettings(e)).isInstanceOf(IllegalStateException.class);}
 @ParameterizedTest @ValueSource(strings={"", "invalid!", "c2hvcnQ="}) void enabledRequiresValidEncryptionKey(String key){assertThatThrownBy(()->new EmailDeliverySettings(valid().withProperty("EMAIL_OUTBOX_ENCRYPTION_KEY",key))).isInstanceOf(IllegalStateException.class);}
 @ParameterizedTest @ValueSource(strings={"identity.sessions.recovery-key","SESSION_RECOVERY_KEY","identity.password-recovery.hmac-key","PASSWORD_RECOVERY_HMAC_KEY"})
 void keysMustBeIndependent(String property){var e=valid();e.setProperty(property,e.getProperty("EMAIL_OUTBOX_ENCRYPTION_KEY"));assertThatThrownBy(()->new EmailDeliverySettings(e)).isInstanceOf(IllegalStateException.class);}
 @ParameterizedTest @ValueSource(strings={"simulation","console","RESEND"}) void rejectsUnknownModes(String mode){assertThatThrownBy(()->new EmailDeliverySettings(valid().withProperty("email.delivery.mode",mode))).isInstanceOf(IllegalStateException.class);}
 @ParameterizedTest @ValueSource(strings={"resend.api-key","resend.from"}) void requiresProviderConfiguration(String property){assertThatThrownBy(()->new EmailDeliverySettings(valid().withProperty(property,""))).isInstanceOf(IllegalStateException.class);}
 @Test void unauthorizedSenderRejected(){assertThatThrownBy(()->new EmailDeliverySettings(valid().withProperty("resend.from","onboarding@resend.dev"))).isInstanceOf(IllegalStateException.class);}
 @Test void leaseMustExceedTimeoutBudget(){assertThatThrownBy(()->new EmailDeliverySettings(valid().withProperty("email.delivery.lease-seconds","10"))).isInstanceOf(IllegalStateException.class);}
 @Test void encryptionIsRandomAuthenticatedAndRestartStable(){byte[] key=Base64.getDecoder().decode(key());var c=new EmailOutboxCipher(key);UUID job=UUID.randomUUID(),emission=UUID.randomUUID();var first=c.encrypt(job,emission,"EMAIL_VERIFICATION","sensitive-code");var second=c.encrypt(job,emission,"EMAIL_VERIFICATION","sensitive-code");assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());assertThat(new String(first.ciphertext(),java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("sensitive-code");assertThat(new EmailOutboxCipher(key).decrypt(job,emission,"EMAIL_VERIFICATION",first)).isEqualTo("sensitive-code");assertThat(first.toString()).doesNotContain("sensitive-code");}
 @Test void alteredCiphertextFailsAuthentication(){var c=new EmailOutboxCipher(Base64.getDecoder().decode(key()));UUID j=UUID.randomUUID(),e=UUID.randomUUID();var env=c.encrypt(j,e,"EMAIL_VERIFICATION","code");byte[] changed=env.ciphertext();changed[0]^=1;assertThatThrownBy(()->c.decrypt(j,e,"EMAIL_VERIFICATION",new EmailOutboxCipher.Envelope(env.nonce(),changed))).hasMessage("Email envelope authentication failed");}
 @Test void incorrectKeyCannotDecrypt(){var c=new EmailOutboxCipher(Base64.getDecoder().decode(key()));UUID j=UUID.randomUUID(),e=UUID.randomUUID();var env=c.encrypt(j,e,"EMAIL_VERIFICATION","code");assertThatThrownBy(()->new EmailOutboxCipher(Base64.getDecoder().decode(key())).decrypt(j,e,"EMAIL_VERIFICATION",env)).isInstanceOf(IllegalStateException.class);}
 @ParameterizedTest @ValueSource(strings={"job","emission","purpose"}) void aadBindsAllIdentities(String changed){var c=new EmailOutboxCipher(Base64.getDecoder().decode(key()));UUID j=UUID.randomUUID(),e=UUID.randomUUID();var env=c.encrypt(j,e,"EMAIL_VERIFICATION","code");assertThatThrownBy(()->c.decrypt(changed.equals("job")?UUID.randomUUID():j,changed.equals("emission")?UUID.randomUUID():e,changed.equals("purpose")?"PASSWORD_RECOVERY":"EMAIL_VERIFICATION",env)).isInstanceOf(IllegalStateException.class);}
 @ParameterizedTest @CsvSource({"EMAIL_VERIFICATION,es","EMAIL_VERIFICATION,en","PASSWORD_RECOVERY,es","PASSWORD_RECOVERY,en"})
 void templatesHaveExactPayloadAndBothRepresentations(String purpose,String language)throws Exception{var json=new ObjectMapper();String payload=SecurityEmailTemplates.payload(json,"ANITEC <mail@notifications.vitalwatch.space>","test@example.invalid",purpose,"000007",language);var n=json.readTree(payload);assertThat(n.size()).isEqualTo(5);assertThat(n.path("html").asText()).contains("000007","lang=\""+language+"\"","10");assertThat(n.path("text").asText()).contains("000007","10");assertThat(n.path("text").asText()).contains(language.equals("en")?"Ignore":"Ignora");assertThat(n.path("subject").asText()).startsWith("ANITEC");assertThat(n.path("to").get(0).asText()).isEqualTo("test@example.invalid");}
 @Test void invalidDynamicCodeCannotBecomeHtml(){assertThatThrownBy(()->SecurityEmailTemplates.payload(new ObjectMapper(),"from","to","EMAIL_VERIFICATION","<script>","es")).isInstanceOf(IllegalArgumentException.class);}
 @Test void credentialsAloneCannotEnableDeliveryOrRecoveryReadiness(){var e=valid().withProperty("email.delivery.mode","disabled");e.setActiveProfiles("prod","test");var sender=new ResendEmailSender(new EmailDeliverySettings(e),e);assertThat(sender.passwordRecoveryConfigured()).isFalse();assertThat(sender.send("private@example.invalid","private-subject","private-code")).isFalse();}

}
