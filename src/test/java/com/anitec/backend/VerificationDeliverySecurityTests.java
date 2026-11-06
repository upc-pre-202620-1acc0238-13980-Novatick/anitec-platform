package com.anitec.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anitec.backend.identity.application.IdentityService;
import com.anitec.backend.identity.application.PasswordEncoderPort;
import com.anitec.backend.identity.domain.*;
import com.anitec.backend.shared.application.DomainEventDispatcher;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VerificationDeliverySecurityTests {
    @Test void registrationStagesWithoutCallingProviderOrClaimingAcceptance() {
        var accounts=mock(AccountRepository.class);var passwords=mock(PasswordEncoderPort.class);
        var sender=mock(com.anitec.backend.identity.application.SecurityEmailDelivery.class);var events=mock(DomainEventDispatcher.class);
        when(passwords.encode(anyString())).thenReturn("synthetic-hash");
        when(accounts.save(any(Account.class))).thenAnswer(call->call.getArgument(0));
        // Registration stages work and never calls the provider.
        var service=new IdentityService(accounts,passwords,sender,events,mock(com.anitec.backend.identity.application.SessionService.class),TestVerificationCodes.authenticator());
        Logger logger=(Logger) LoggerFactory.getLogger(IdentityService.class);var appender=new ListAppender<ILoggingEvent>();
        appender.start();logger.addAppender(appender);
        try {
            var result=service.register(new IdentityService.RegisterCommand("Test","Delivery","delivery@example.invalid","Test-Password1!",Role.GANADERO));
            assertThat(result.emailDelivery()).isEqualTo("UNCONFIRMED");
            assertThat(appender.list).isEmpty();
            verify(sender).verification(any(Account.class), eq(EmailLanguage.ES));
        } finally {logger.detachAppender(appender);appender.stop();}
    }
}
