package com.anitec.backend;

import com.anitec.backend.identity.application.SecurityEmailDelivery;
import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.identity.infrastructure.*;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only observation at the issuance boundary, never reads a code from persistence. */
public final class TestVerificationCodes {
 public static final String KEY;
 static {byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);KEY=Base64.getEncoder().encodeToString(bytes);}
 private static final Map<UUID,String> issued=new ConcurrentHashMap<>();
 public static EmailVerificationHmac authenticator(){return new EmailVerificationHmac(new MockEnvironment().withProperty("EMAIL_VERIFICATION_HMAC_KEY",KEY));}
 public static String code(Account account){String value=account.getVerificationCode();if(value==null)value=issued.get(account.getVerificationEmissionId());if(value==null)throw new IllegalStateException("No captured test verification emission");return value;}
 static void capture(ConfigurableApplicationContext context){context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor(){
  @Override public Object postProcessAfterInitialization(Object bean,String name){
   if(!(bean instanceof SecurityEmailDelivery))return bean;
   var proxy=new ProxyFactory(bean);proxy.setProxyTargetClass(bean instanceof JdbcSecurityEmailOutbox);
   proxy.addAdvice((MethodInterceptor) call->{
    if(call.getMethod().getName().equals("verification") && call.getArguments()[0] instanceof Account a && a.getVerificationCode()!=null)
     issued.put(a.getVerificationEmissionId(),a.getVerificationCode());
    return call.proceed();
   });return proxy.getProxy();
  }
 });}
}
