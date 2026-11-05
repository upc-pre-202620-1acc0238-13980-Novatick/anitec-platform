package com.anitec.backend.identity.infrastructure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Validate before Flyway can invalidate legacy codes, including disabled-mail and mixed-profile starts. */
public class EmailVerificationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder(){return ConfigDataEnvironmentPostProcessor.ORDER+2;}
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment,SpringApplication application){
        EmailVerificationHmac.validatedKey(environment);
    }
}
