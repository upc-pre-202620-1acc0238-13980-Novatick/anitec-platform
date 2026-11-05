package com.anitec.backend;

import com.anitec.backend.identity.domain.*;
import com.anitec.backend.identity.infrastructure.*;
import com.anitec.backend.identity.interfaceapi.PasswordRecoveryOriginLimiter;
import com.anitec.backend.shared.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PasswordRecoverySecurityTests {
 String key(){byte[] k=new byte[32];new java.security.SecureRandom().nextBytes(k);return Base64.getEncoder().encodeToString(k);}
 EmailSender controlled(boolean ready){return new EmailSender(){public boolean send(String a,String b,String c){return true;}public boolean passwordRecoveryConfigured(){return ready;}};}
 MockEnvironment env(String...profiles){var e=new MockEnvironment();e.setActiveProfiles(profiles);return e;}
 @Test void disabledNeedsNeitherKeyNorMailer(){var s=new PasswordRecoverySecurity(false,"","",controlled(false),env("prod"));assertThatThrownBy(s::requireAvailable).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.errorCode()).isEqualTo("RECOVERY_UNAVAILABLE"));}
 @Test void disabledDoesNotParseUnusedMalformedKey(){var s=new PasswordRecoverySecurity(false,"not-base64!","",controlled(false),env("dev"));assertThatThrownBy(s::requireAvailable).isInstanceOf(DomainException.class);}
 @Test void enabledRequiresKeyEvenForTests(){assertThatThrownBy(()->new PasswordRecoverySecurity(true,"","",controlled(true),env("test"))).isInstanceOf(IllegalStateException.class);}
 @Test void invalidEncodingAndLengthAreRejected(){for(String k:List.of("not-base64!",Base64.getEncoder().encodeToString(new byte[16]),Base64.getEncoder().encodeToString(new byte[64])))assertThatThrownBy(()->new PasswordRecoverySecurity(true,k,"",controlled(true),env("test"))).isInstanceOf(IllegalStateException.class);}
 @Test void sessionKeyCannotBeReused(){String k=key();assertThatThrownBy(()->new PasswordRecoverySecurity(true,k,k,controlled(true),env("test"))).isInstanceOf(IllegalStateException.class);}
 @Test void controlledMailerOnlyAllowedInTestProfile(){for(String[] profiles:List.of(new String[]{"prod"},new String[]{"dev"},new String[]{"prod","test"},new String[]{}))assertThatThrownBy(()->new PasswordRecoverySecurity(true,key(),"",controlled(true),env(profiles))).isInstanceOf(IllegalStateException.class);}
 @Test void isolatedTestMailerMustStillBeExplicitlyReady(){assertThatThrownBy(()->new PasswordRecoverySecurity(true,key(),"",controlled(false),env("test"))).isInstanceOf(IllegalStateException.class);}
 @Test void unconfiguredRealAdapterCannotEnableRecovery(){var sender=new ResendEmailSender("","ANITEC <mail@notifications.vitalwatch.space>",env("prod"));assertThatThrownBy(()->new PasswordRecoverySecurity(true,key(),"",sender,env("prod"))).isInstanceOf(IllegalStateException.class);}
 @Test void sandboxDefaultSenderIsNotReady(){var sender=new ResendEmailSender("synthetic-provider-value","ANITEC <onboarding@resend.dev>",env("test"));assertThat(sender.passwordRecoveryConfigured()).isFalse();}
 @Test void realAdapterLocalReadinessDoesNotRequireLiveNetwork(){var sender=new ResendEmailSender("synthetic-provider-value","ANITEC <mail@notifications.vitalwatch.space>",env("prod"));assertThat(sender.passwordRecoveryConfigured()).isTrue();new PasswordRecoverySecurity(true,key(),key(),sender,env("prod")).requireAvailable();}
 @Test void malformedFromAddressIsNotReady(){var sender=new ResendEmailSender("synthetic-provider-value","ANITEC <mail@notifications.vitalwatch.space",env("prod"));assertThat(sender.passwordRecoveryConfigured()).isFalse();}
 @Test void hmacBindsAccountPurposeEmissionAndCode(){var s=new PasswordRecoverySecurity(true,key(),"",controlled(true),env("test"));UUID a=UUID.randomUUID(),b=UUID.randomUUID();byte[] mac=s.sign(a,b,"000007");assertThat(mac.length).isEqualTo(32);assertThat(Arrays.equals(mac,s.sign(a,b,"000007"))).isTrue();assertThat(Arrays.equals(mac,s.sign(UUID.randomUUID(),b,"000007"))).isFalse();assertThat(Arrays.equals(mac,s.sign(a,UUID.randomUUID(),"000007"))).isFalse();assertThat(Arrays.equals(mac,s.sign(a,b,"000008"))).isFalse();}
 @Test void hmacIsStableWithConfiguredKeyAcrossRestart(){String key=key();UUID a=UUID.randomUUID(),b=UUID.randomUUID();var x=new PasswordRecoverySecurity(true,key,"",controlled(true),env("test"));var y=new PasswordRecoverySecurity(true,key,"",controlled(true),env("test"));assertThat(Arrays.equals(x.sign(a,b,"000007"),y.sign(a,b,"000007"))).isTrue();}
 @Test void sixDigitGeneratorUsesAscii(){var g=new PasswordRecoveryCodeGenerator();for(int i=0;i<1000;i++)assertThat(g.generate().matches("[0-9]{6}")).isTrue();}
 @Test void originCountersSeparateRequestAndConfirmation(){var limiter=new PasswordRecoveryOriginLimiter(Clock.fixed(Instant.parse("2035-01-01T00:00:00Z"),ZoneOffset.UTC),2);limiter.check("one","request");limiter.check("one","request");assertThatThrownBy(()->limiter.check("one","request")).isInstanceOfSatisfying(VerificationThrottle.class,e->assertThat(e.status().value()).isEqualTo(429));limiter.check("one","confirm");limiter.check("two","request");}
 @Test void verificationOriginCounterIsIndependent(){Clock clock=Clock.fixed(Instant.parse("2035-01-01T00:00:00Z"),ZoneOffset.UTC);var recovery=new PasswordRecoveryOriginLimiter(clock,1);var verification=new com.anitec.backend.identity.interfaceapi.VerificationOriginLimiter(clock,1);recovery.check("one","request");assertThatThrownBy(()->recovery.check("one","request")).isInstanceOf(VerificationThrottle.class);verification.check("one","request");}

 @Test void springStartupRejectsEnabledRecoveryWithoutKey(){new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(PasswordRecoverySecurity.class).withBean(EmailSender.class,()->controlled(true)).withPropertyValues("spring.profiles.active=test","identity.password-recovery.enabled=true","identity.password-recovery.hmac-key=","identity.sessions.recovery-key=").run(context->assertThat(context.getStartupFailure()).isNotNull());}
 @Test void hmacPurposeCannotBeSubstituted()throws Exception{String encoded=key();UUID account=UUID.randomUUID(),emission=UUID.randomUUID();var s=new PasswordRecoverySecurity(true,encoded,"",controlled(true),env("test"));var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(encoded),"HmacSHA256"));byte[] different=mac.doFinal((account+"\0EMAIL_VERIFICATION\0"+emission+"\0"+"000007").getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThat(Arrays.equals(s.sign(account,emission,"000007"),different)).isFalse();}
}
