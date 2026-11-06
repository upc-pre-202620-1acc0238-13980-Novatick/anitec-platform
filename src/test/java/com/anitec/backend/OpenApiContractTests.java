package com.anitec.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.swagger.v3.core.util.Yaml;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers @ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "email.delivery.mode=disabled","email.delivery.worker-enabled=false","identity.password-recovery.enabled=false",
        "payments.simulation-enabled=false","spring.flyway.placeholders.demo_seed=false","logging.level.root=ERROR"})
class OpenApiContractTests {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired com.anitec.backend.identity.domain.AccountRepository accounts;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    static final Path SPEC=Path.of("docs/api/openapi.yaml"),PUBLIC=Path.of("docs/api/site/openapi-public.json");
    JsonNode generated()throws Exception{
        var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);return json.readTree(r.body());
    }
    static JsonNode canonical(JsonNode node){return canonical(node,"");}
    static JsonNode canonical(JsonNode node,String key){
        if(node.isObject()){var out=JsonNodeFactory.instance.objectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);for(var field:keys)out.set(field,canonical(node.get(field),field));return out;}
        if(node.isArray()){var values=new ArrayList<JsonNode>();node.forEach(n->values.add(canonical(n,"")));if(Set.of("tags","required","enum","security","parameters").contains(key))values.sort(Comparator.comparing(JsonNode::toString));var out=JsonNodeFactory.instance.arrayNode();values.forEach(out::add);return out;}return node;
    }
    static JsonNode publicCopy(JsonNode source){var out=(ObjectNode)source.deepCopy();
        out.set("servers",JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("url","https://api.example.invalid").put("description","Referencia inerte; consulta académica, sin ejecución")));
        return canonical(out);
    }
    @Test void versionedSpecificationAndPublicCopyMatchGeneratedApi()throws Exception{
        JsonNode actual=canonical(generated());
        if(Boolean.getBoolean("anitec.openapi.update")){
            assertThat(System.getenv("CI")).as("CI must verify, never overwrite the contract").isNotEqualTo("true");
            Files.createDirectories(SPEC.getParent());Files.createDirectories(PUBLIC.getParent());
            Files.writeString(SPEC,Yaml.mapper().writeValueAsString(actual));
            Files.writeString(PUBLIC,json.writerWithDefaultPrettyPrinter().writeValueAsString(publicCopy(actual))+"\n");
        }
        assertThat(canonical(Yaml.mapper().readTree(Files.readString(SPEC)))).as("Run the documented explicit update, review and commit contract changes").isEqualTo(actual);
        assertThat(canonical(json.readTree(Files.readString(PUBLIC)))).isEqualTo(publicCopy(actual));
    }
    @Test void specificationCoversExactlyTheRealProductMappings()throws Exception{
        var expected=new TreeSet<String>();mappings.getHandlerMethods().forEach((mapping,method)->{
            if(!method.getBeanType().getPackageName().startsWith("com.anitec.backend"))return;
            for(var path:mapping.getPatternValues())if(path.startsWith("/api/v1/"))for(var verb:mapping.getMethodsCondition().getMethods())expected.add(verb.name()+" "+path);
        });
        var actual=new TreeSet<String>();generated().path("paths").fields().forEachRemaining(p->p.getValue().fieldNames().forEachRemaining(m->{if(Set.of("get","post","put","patch","delete").contains(m))actual.add(m.toUpperCase(Locale.ROOT)+" "+p.getKey());}));
        assertThat(actual).isEqualTo(expected);assertThat(actual).isNotEmpty();
    }
    JsonNode resolve(JsonNode spec,JsonNode schema){return schema.has("$ref")?spec.at(schema.path("$ref").asText().substring(1)):schema;}
    void referencesExist(JsonNode spec,JsonNode node){
        if(node.isObject()){if(node.has("$ref")){String ref=node.path("$ref").asText();assertThat(ref).startsWith("#/components/");assertThat(spec.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();}node.elements().forEachRemaining(n->referencesExist(spec,n));}
        else if(node.isArray())node.forEach(n->referencesExist(spec,n));
    }
    @Test void referencesRequestRecordsRolesAndDistinctEnumsAreComplete()throws Exception{
        var spec=generated();referencesExist(spec,spec);
        assertThat(spec.path("paths").has("/actuator/health")).isFalse();
        assertThat(spec.path("paths").fieldNames()).toIterable().allSatisfy(p->assertThat(p).startsWith("/api/v1/"));
        assertThat(spec.at("/components/schemas/AnimalStatusRequest/properties/status/enum").toString()).contains("ACTIVO","FALLECIDO").doesNotContain("COMPLETADA");
        assertThat(spec.at("/components/schemas/VisitStatusRequest/properties/status/enum").toString()).contains("COMPLETADA","CANCELADA").doesNotContain("ACTIVO","PROGRAMADA");
        spec.path("paths").fields().forEachRemaining(p->p.getValue().fields().forEachRemaining(o->{
            if(!Set.of("get","post","put","patch","delete").contains(o.getKey()))return;
            assertThat(o.getValue().has("x-roles")).isTrue();
            assertThat(o.getValue().path("security").isEmpty()).isEqualTo(p.getKey().startsWith("/api/v1/auth/"));
        }));
        mappings.getHandlerMethods().forEach((mapping,method)->{
            for(var parameter:method.getMethodParameters())if(parameter.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class)&&parameter.getParameterType().isRecord()){
                Set<String> expected=new TreeSet<>();for(var c:parameter.getParameterType().getRecordComponents())expected.add(c.getName());
                for(var path:mapping.getPatternValues())for(var verb:mapping.getMethodsCondition().getMethods()){
                    var schema=resolve(spec,spec.path("paths").path(path).path(verb.name().toLowerCase(Locale.ROOT)).path("requestBody").path("content").path("application/json").path("schema"));
                    var fields=new TreeSet<String>();schema.path("properties").fieldNames().forEachRemaining(fields::add);assertThat(fields).as(path).isEqualTo(expected);
                }
            }
        });
    }
    record Response(int status,JsonNode body){}
    Response call(String method,String path,Object body,String token)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");if(token!=null)b.header("Authorization","Bearer "+token);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var r=HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());return new Response(r.statusCode(),r.body().isBlank()?json.createObjectNode():json.readTree(r.body()));
    }
    void covered(JsonNode spec,String method,String path,Response response){
        var declared=spec.path("paths").path(path).path(method).path("responses").path(Integer.toString(response.status()));assertThat(declared.isMissingNode()).as(method+" "+path+" status "+response.status()).isFalse();
        checkShape(spec,declared.path("content").path("application/json").path("schema"),response.body());
    }
    void checkShape(JsonNode spec,JsonNode schema,JsonNode value){
        schema=resolve(spec,schema);
        if(value.isNull()){assertThat(schema.path("nullable").asBoolean()).as("nullable response property").isTrue();return;}
        if(schema.has("enum"))assertThat(schema.path("enum")).contains(value);
        if(schema.path("type").asText().equals("object")){
            for(var field:schema.path("required"))assertThat(value.has(field.asText())).as("required response field "+field.asText()).isTrue();
            var fields=value.fields();while(fields.hasNext()){var f=fields.next();var property=schema.path("properties").path(f.getKey());if(!property.isMissingNode())checkShape(spec,property,f.getValue());}
        }else if(schema.path("type").asText().equals("array"))for(var child:value)checkShape(spec,schema.path("items"),child);
        else if(schema.path("type").asText().equals("string"))assertThat(value.isTextual()).isTrue();
        else if(schema.path("type").asText().equals("boolean"))assertThat(value.isBoolean()).isTrue();
        else if(schema.path("type").asText().equals("integer"))assertThat(value.isIntegralNumber()).isTrue();
    }
    @Test void liveStatusesAndPreviouslyUntypedMapsMatchTheDocumentedShapes()throws Exception{
        var spec=generated();String email=UUID.randomUUID()+"@example.invalid",password="Synthetic-"+UUID.randomUUID()+"aA1!";
        var body=Map.of("name","Contract","lastName","Test","email",email,"password",password,"role","GANADERO");
        var registered=call("POST","/api/v1/auth/register",body,null);assertThat(registered.status()).isEqualTo(201);covered(spec,"post","/api/v1/auth/register",registered);
        var duplicate=call("POST","/api/v1/auth/register",body,null);assertThat(duplicate.status()).isEqualTo(409);covered(spec,"post","/api/v1/auth/register",duplicate);
        var unavailable=call("POST","/api/v1/auth/password-recovery/request",Map.of("email",email),null);assertThat(unavailable.status()).isEqualTo(503);covered(spec,"post","/api/v1/auth/password-recovery/request",unavailable);
        var privateRoute=call("GET","/api/v1/account/me",null,null);assertThat(privateRoute.status()).isEqualTo(401);covered(spec,"get","/api/v1/account/me",privateRoute);
        var generic=call("POST","/api/v1/auth/resend-code",Map.of("email",email),null);assertThat(generic.status()).isEqualTo(202);covered(spec,"post","/api/v1/auth/resend-code",generic);assertThat(generic.body().has("data")).isFalse();
        String code=TestVerificationCodes.code(accounts.findByEmail(email).orElseThrow());
        var verified=call("POST","/api/v1/auth/verify",Map.of("email",email,"code",code),null);assertThat(verified.status()).isEqualTo(200);covered(spec,"post","/api/v1/auth/verify",verified);
        var login=call("POST","/api/v1/auth/login",Map.of("email",email,"password",password),null);assertThat(login.status()).isEqualTo(200);covered(spec,"post","/api/v1/auth/login",login);String token=login.body().path("data").path("accessToken").asText();
        for(String path:List.of("/api/v1/animals/capacity","/api/v1/notifications","/api/v1/subscription/my-subscription")){var result=call("GET",path,null,token);assertThat(result.status()).isEqualTo(200);covered(spec,"get",path,result);}
    }
}
