package com.anitec.backend;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers @ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"identity.password-recovery.enabled=false","resend.api-key=","identity.password-recovery.hmac-key="})
class PasswordRecoveryDisabledTests {
 @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
 @Autowired TestRestTemplate http;@Autowired JdbcTemplate jdbc;
 @Test void requestUnavailableWithoutKeyOrMailerDoesNotCreateIssuance(){for(String email:List.of("absent@example.invalid","other@example.invalid")){var r=http.postForEntity("/api/v1/auth/password-recovery/request",Map.of("email",email),JsonNode.class);assertThat(r.getStatusCode().value()).isEqualTo(503);assertThat(r.getBody().path("errorCode").asText()).isEqualTo("RECOVERY_UNAVAILABLE");}assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_password_recovery",Integer.class)).isZero();}
 @Test void confirmationUnavailableAndNoNewTokens(){int before=jdbc.queryForObject("SELECT count(*) FROM identity_sessions",Integer.class);var r=http.postForEntity("/api/v1/auth/password-recovery/confirm",Map.of("email","absent@example.invalid","code","123456","newPassword","Updated-Password2!"),JsonNode.class);assertThat(r.getStatusCode().value()).isEqualTo(503);assertThat(r.getBody().path("errorCode").asText()).isEqualTo("RECOVERY_UNAVAILABLE");assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_sessions",Integer.class)).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_password_recovery",Integer.class)).isZero();}

 @Test void existingCodeCannotBeConsumedWhileGloballyDisabled(){String email=UUID.randomUUID()+"@example.invalid";UUID id=jdbc.queryForObject("INSERT INTO identity_accounts(email,password_hash,name,last_name,role,status,verified_at) VALUES (?,'preserved-hash','Test','Disabled','GANADERO','ACTIVA',now()) RETURNING id",UUID.class,email);try{jdbc.update("INSERT INTO identity_password_recovery(account_id,purpose,window_started_at,emission_count,last_issued_at,emission_id,code_verifier,expires_at,failed_attempts) VALUES (?,'PASSWORD_RECOVERY',now(),1,now(),?,?,now()+interval '10 minutes',0)",id,UUID.randomUUID(),new byte[32]);var r=http.postForEntity("/api/v1/auth/password-recovery/confirm",Map.of("email",email,"code","000007","newPassword","Updated-Password2!"),JsonNode.class);assertThat(r.getStatusCode().value()).isEqualTo(503);assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL AND failed_attempts=0 FROM identity_password_recovery WHERE account_id=?",Boolean.class,id)).isTrue();assertThat(jdbc.queryForObject("SELECT password_hash FROM identity_accounts WHERE id=?",String.class,id)).isEqualTo("preserved-hash");}finally{jdbc.update("DELETE FROM identity_password_recovery WHERE account_id=?",id);}}
}
