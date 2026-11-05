package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.application.IdentityService.TokenPair;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** AES-256-GCM, random 96-bit nonce, 128-bit tag; AAD binds result to session/token/operation. */
@Component
public class SessionCryptography implements com.anitec.backend.identity.application.SessionRecoveryCipher {
 private final byte[] key;
 private final ObjectMapper json;
 private final SecureRandom random=new SecureRandom();
 public SessionCryptography(@Value("${identity.sessions.recovery-key:${SESSION_RECOVERY_KEY:}}") String configured,Environment env,ObjectMapper json) {
   this.json=json;
   if(configured.isBlank()) {
     if(Arrays.asList(env.getActiveProfiles()).contains("prod") || !Arrays.asList(env.getActiveProfiles()).contains("test"))
       throw new IllegalStateException("SESSION_RECOVERY_KEY is required outside isolated tests");
     key=new byte[32];random.nextBytes(key); // Isolated tests only; deployments must configure the shared key.
   } else {
     try { key=Base64.getDecoder().decode(configured); } catch(IllegalArgumentException ex) { throw new IllegalStateException("Invalid session recovery key encoding"); }
     if(key.length!=32) throw new IllegalStateException("Session recovery key must contain 32 random bytes");
   }
 }
 public byte[] encrypt(TokenPair pair,String context) {
   try {
     byte[] nonce=new byte[12];random.nextBytes(nonce);
     Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
     c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
     byte[] encrypted=c.doFinal(json.writeValueAsBytes(pair));
     return ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array();
   } catch(Exception ex) { throw new IllegalStateException("Session recovery encryption unavailable"); }
 }
 public TokenPair decrypt(byte[] encrypted,String context) {
   try {
     Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(encrypted,12)));
     c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
     return json.readValue(c.doFinal(Arrays.copyOfRange(encrypted,12,encrypted.length)),TokenPair.class);
   } catch(Exception ex) { throw new IllegalStateException("Session recovery decryption unavailable"); }
 }
}
