package com.anitec.backend;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
import java.util.Map;
/** Exists only in test classes: no fallback key or opt-out exists in the shipped backend. */
public class TestVerificationEnvironment implements EnvironmentPostProcessor,Ordered {
 public int getOrder(){return ConfigDataEnvironmentPostProcessor.ORDER+1;}
 public void postProcessEnvironment(ConfigurableEnvironment env,SpringApplication app){
  if(!env.getProperty("anitec.tests.omit-verification-key",Boolean.class,false))
   env.getPropertySources().addLast(new MapPropertySource("isolated-test-verification-key",Map.of("identity.email-verification.hmac-key",TestVerificationCodes.KEY)));
  app.addInitializers(TestVerificationCodes::capture);
 }
}
