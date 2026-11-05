package com.anitec.backend;
import com.anitec.backend.identity.application.IdentityService.TokenPair;
import com.anitec.backend.identity.infrastructure.SessionCryptography;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SessionCryptographyTests {
 byte[] key(){byte[] k=new byte[32];new java.security.SecureRandom().nextBytes(k);return k;}
 SessionCryptography cipher(byte[] key){return new SessionCryptography(Base64.getEncoder().encodeToString(key),new MockEnvironment().withProperty("spring.profiles.active","prod"),new ObjectMapper());}
 @Test void authenticatedEncryptionSurvivesRestartWithConfiguredKey(){byte[] key=key();var c=cipher(key);var pair=new TokenPair("synthetic-access","synthetic-refresh",42);byte[] encrypted=c.encrypt(pair,"bound-context");assertThat(cipher(key).decrypt(encrypted,"bound-context").equals(pair)).isTrue();}
 @Test void alteredCiphertextFailsClosed(){var c=cipher(key());byte[] encrypted=c.encrypt(new TokenPair("synthetic-access","synthetic-refresh",42),"context");encrypted[encrypted.length-1]^=1;assertThatThrownBy(()->c.decrypt(encrypted,"context")).isInstanceOf(IllegalStateException.class);}
 @Test void anotherSessionContextCannotDecrypt(){var c=cipher(key());byte[] encrypted=c.encrypt(new TokenPair("synthetic-access","synthetic-refresh",42),"session-A");assertThatThrownBy(()->c.decrypt(encrypted,"session-B")).isInstanceOf(IllegalStateException.class);}
 @Test void anotherKeyCannotDecrypt(){byte[] encrypted=cipher(key()).encrypt(new TokenPair("synthetic-access","synthetic-refresh",42),"context");assertThatThrownBy(()->cipher(key()).decrypt(encrypted,"context")).isInstanceOf(IllegalStateException.class);}
 @Test void randomNoncesPreventRepeatedCiphertext(){var c=cipher(key());var pair=new TokenPair("synthetic-access","synthetic-refresh",42);assertThat(Arrays.equals(c.encrypt(pair,"context"),c.encrypt(pair,"context"))).isFalse();}
 @Test void productionCombinedProfilesRequireKey(){var env=new MockEnvironment();env.setActiveProfiles("test","prod","dev");assertThatThrownBy(()->new SessionCryptography("",env,new ObjectMapper())).isInstanceOf(IllegalStateException.class);}
 @Test void malformedOrWeakConfiguredKeyIsRejected(){assertThatThrownBy(()->new SessionCryptography("invalid-base64!",new MockEnvironment(),new ObjectMapper())).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->new SessionCryptography(Base64.getEncoder().encodeToString(new byte[16]),new MockEnvironment(),new ObjectMapper())).isInstanceOf(IllegalStateException.class);}

 @Test void developmentAlsoRequiresConfiguredKey(){var env=new MockEnvironment();env.setActiveProfiles("dev");assertThatThrownBy(()->new SessionCryptography("",env,new ObjectMapper())).isInstanceOf(IllegalStateException.class);}
}
