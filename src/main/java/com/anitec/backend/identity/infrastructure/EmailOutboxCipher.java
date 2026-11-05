package com.anitec.backend.identity.infrastructure;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

/** Versioned AES-256-GCM envelope. AAD binds the ciphertext to its immutable identity. */
public final class EmailOutboxCipher {
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    public record Envelope(byte[] nonce, byte[] ciphertext) {
        public Envelope { nonce=nonce.clone(); ciphertext=ciphertext.clone(); }
        @Override public byte[] nonce(){return nonce.clone();}
        @Override public byte[] ciphertext(){return ciphertext.clone();}
        @Override public String toString(){return "EncryptedEmailEnvelope[REDACTED]";}
    }
    public EmailOutboxCipher(byte[] key) {
        if(key.length!=32) throw new IllegalArgumentException("AES-256 requires 32 bytes"); this.key=key.clone();
    }
    public Envelope encrypt(UUID job,UUID emission,String purpose,String payload) {
        byte[] nonce=new byte[12];random.nextBytes(nonce);
        return new Envelope(nonce,crypt(Cipher.ENCRYPT_MODE,job,emission,purpose,nonce,payload.getBytes(StandardCharsets.UTF_8)));
    }
    public String decrypt(UUID job,UUID emission,String purpose,Envelope envelope) {
        return new String(crypt(Cipher.DECRYPT_MODE,job,emission,purpose,envelope.nonce(),envelope.ciphertext()),StandardCharsets.UTF_8);
    }
    private byte[] crypt(int mode,UUID job,UUID emission,String purpose,byte[] nonce,byte[] value) {
        try {
            if(nonce.length!=12) throw new IllegalArgumentException();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            c.updateAAD(("anitec-email/v1\0"+job+"\0"+emission+"\0"+purpose).getBytes(StandardCharsets.UTF_8));
            return c.doFinal(value);
        } catch(Exception invalid) { throw new IllegalStateException("Email envelope authentication failed"); }
    }
}
