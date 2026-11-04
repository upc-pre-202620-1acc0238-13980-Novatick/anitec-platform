package com.anitec.backend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anitec.backend.identity.infrastructure.ResendEmailSender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSecurityTests {
    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev", "test"})
    void missingProviderNeverLogsMessageOrReportsDelivery(String profile) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        Logger logger = (Logger) LoggerFactory.getLogger(ResendEmailSender.class);
        Level previousLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            var sender = new ResendEmailSender(" ", "", environment);
            assertThat(sender.send("private@example.invalid", "private-subject", "private-code-and-body")).isFalse();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .doesNotContain("private@example.invalid", "private-subject", "private-code-and-body");
            assertThat(appender.list.getFirst().getLevel())
                    .isEqualTo(profile.equals("prod") ? Level.WARN : Level.INFO);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
