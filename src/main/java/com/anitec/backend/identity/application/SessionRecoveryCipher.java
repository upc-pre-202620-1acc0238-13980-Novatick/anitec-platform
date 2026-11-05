package com.anitec.backend.identity.application;
import com.anitec.backend.identity.application.IdentityService.TokenPair;
public interface SessionRecoveryCipher {
 byte[] encrypt(TokenPair pair,String context);
 TokenPair decrypt(byte[] encrypted,String context);
}
