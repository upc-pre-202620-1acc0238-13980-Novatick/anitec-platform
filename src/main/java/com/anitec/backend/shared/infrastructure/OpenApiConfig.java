package com.anitec.backend.shared.infrastructure;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import com.fasterxml.jackson.core.type.TypeReference;
import com.anitec.backend.identity.application.IdentityService;
import com.anitec.backend.notification.application.NotificationService;
import org.springdoc.core.customizers.OpenApiCustomizer;
import java.util.*;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentation only. Runtime security/DTOs are unchanged; staging still disables live docs.
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI anitecOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ANITEC API")
                        .version("1.0.0-mvp-candidate")
                        .description("ANITEC: API v1 para GANADERO y VETERINARIO. Cinco bounded contexts y bandeja in-app. "
                                + "Simulación académica. No se realizará ningún cobro. No hay Stripe, Google Play Billing ni FCM real. "
                                + "Fechas Instant en UTC; fechas clínicas y nacimiento como yyyy-MM-dd. "
                                + "El sobre omite sus campos nulos; los DTO anidados pueden contener null según su esquema. Un campo opcional de entrada puede admitir null. "
                                + "Los permisos dependen además de propiedad, vinculación activa y autoría. "
                                + "En prod+staging todas las operaciones requieren además la barrera privada de staging; nunca distribuir su clave en una APK."))
                .servers(List.of(new Server().url("/").description("Origen configurado por el equipo; no implica un servidor público")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .name(SECURITY_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    /** Describe existing map responses without changing the REST implementation. */
    public record InventoryCapacityView(UUID ownerId,int allowedAnimals,int activeAnimals,long lastPlanRevision) {}
    public record RevocationView(UUID linkId,String status,Instant revokedAt) {}
    public record NotificationPage(List<NotificationService.NotificationView> content,int page,int size,long total) {}

    @Bean
    public OpenApiCustomizer approvedContracts() {
        return api -> {
            var schemas=api.getComponents().getSchemas();
            if(schemas==null){schemas=new LinkedHashMap<>();api.getComponents().setSchemas(schemas);}
            var message=envelope(null);
            schemas.put("ApiMessage",message);
            schemas.put("ApiError",new ObjectSchema()
                    .addProperty("success",new BooleanSchema()._enum(List.of(false)))
                    .addProperty("message",new StringSchema()).addProperty("errorCode",new StringSchema())
                    .addProperty("details",new ArraySchema().items(new Schema<>().$ref("#/components/schemas/FieldError")))
                    .addProperty("timestamp",new DateTimeSchema()).required(List.of("success","message","errorCode","timestamp")));
            Schema<?> capacity=model(api,InventoryCapacityView.class);
            Schema<?> revocation=model(api,RevocationView.class);
            Schema<?> notifications=model(api,NotificationPage.class);
            Schema<?> delivery=model(api,new TypeReference<com.anitec.backend.shared.interfaceapi.ApiResponse<IdentityService.EmailDelivery>>(){}.getType());
            api.getPaths().forEach((path,item)->item.readOperationsMap().forEach((method,operation)->{
                String verb=method.name();boolean publicAuth=path.startsWith("/api/v1/auth/");
                operation.setOperationId(verb.toLowerCase(Locale.ROOT)+path.replaceAll("[{}]","").replaceAll("[^a-zA-Z0-9]","_"));
                operation.setSecurity(publicAuth?List.of():List.of(new SecurityRequirement().addList(SECURITY_SCHEME)));
                List<String> roles=roles(verb,path);
                operation.addExtension("x-roles",roles);
                operation.addExtension("x-module",switch(path.split("/")[3]){
                    case "auth","account" -> "identity";
                    case "animals","farms","species" -> "livestock";
                    case "invitations","vet-links" -> "linking";
                    case "visits","attentions" -> "care";
                    case "subscription" -> "subscriptions";
                    default -> "notification";
                });
                String note="Autorización: "+(publicAuth?"sin JWT; las credenciales/códigos de cada operación siguen siendo obligatorios cuando corresponda":String.join(" o ",roles)+", con cuenta activa/verificada y sesión vigente")+". ";
                if(path.startsWith("/api/v1/animals")||path.startsWith("/api/v1/farms")||path.startsWith("/api/v1/attentions"))
                    note+="La lectura se limita al propietario o veterinario con vinculación activa; las escrituras clínicas requieren veterinario autorizado y autoría según la operación. ";
                if(path.startsWith("/api/v1/subscription"))note+="Precios y capacidades del servidor. Checkout opaco, sin cobro real. Simulador solo dev/test con opt-in; prod/staging nunca lo habilitan. ";
                if(path.equals("/api/v1/subscription/premium"))note+="renewal=true prepara una nueva renovación explícita; ausente/null/false conserva contratación. Preparar no activa Premium. ";
                if(path.equals("/api/v1/subscription/my-subscription"))note+="Reconcilia vencimientos antes de responder. Sin consulta, otros contextos pueden retener el límite anterior hasta el barrido de 30 minutos, si está operativo. ";
                if(path.contains("password-recovery"))note+="Deshabilitado por defecto: 503 RECOVERY_UNAVAILABLE. Solicitar devuelve 202 genérico, no confirma entrega. Confirmar devuelve 204 sin tokens y exige nuevo login. ";
                if(path.equals("/api/v1/auth/register")||path.equals("/api/v1/auth/resend-code"))note+="UNCONFIRMED no prueba envío ni entrega. Código numérico de seis dígitos, vigencia 10 minutos y cinco intentos. Reenvíos autorizados: cinco por ventana fija de 24 horas, espera 60 segundos; sin contraseña válida la respuesta 202 es genérica y no emite código. ";
                if(path.equals("/api/v1/auth/refresh-token"))note+="operationId es UUID v4. Conservar refreshToken y operationId al reintentar la misma rotación durante 60 segundos; otra operación o reutilización fuera de tolerancia puede revocar esa sesión. expiresIn está en segundos. ";
                if(path.equals("/api/v1/auth/logout"))note+="Revoca solo la sesión acreditada por refreshToken y sus access tokens de inmediato; no necesita JWT ni cierra las otras sesiones. ";
                if(path.equals("/api/v1/invitations"))note+="Solo destinatario VETERINARIO activo y verificado; revalidado al aceptar, sin revelar estados privados. Correo directo: SENT es aceptación, UNCONFIRMED no confirma envío. ";
                if(path.startsWith("/api/v1/notifications"))note+="Bandeja interna persistida; no FCM. Lista solo del destinatario; page>=0, size se limita a 1..100. ";
                operation.setDescription(note+Objects.toString(operation.getDescription(),""));
                if(operation.getParameters()!=null)operation.getParameters().forEach(p->{if("Accept-Language".equals(p.getName()))p.setDescription("Opcional: es/en, variantes regionales y pesos; español si falta, es inválido o incompatible. Selección de esta comunicación, conservada en el payload asíncrono; no preferencia de cuenta. En invitaciones decide el emisor.");});
                String success=success(verb,path);
                var old=operation.getResponses().get("200");
                operation.setResponses(new ApiResponses());
                ApiResponse ok=old==null?new ApiResponse():old;
                if(ok.getContent()!=null&&!ok.getContent().isEmpty()){
                    Schema<?> body=ok.getContent().values().iterator().next().getSchema();
                    ok.setContent(new Content().addMediaType("application/json",new MediaType().schema(body)));
                }
                ok.setDescription(success.equals("201")?"Recurso creado":success.equals("202")?"Solicitud aceptada; no garantiza envío ni entrega":success.equals("204")?"Contraseña restablecida; sin cuerpo ni tokens":"Operación correcta");
                if(success.equals("204"))ok.setContent(null);
                operation.getResponses().addApiResponse(success,ok);
                if(path.equals("/api/v1/auth/verify")||path.equals("/api/v1/auth/logout")||path.equals("/api/v1/auth/password-recovery/request"))response(operation,success,new Schema<>().$ref("#/components/schemas/ApiMessage"));
                if(path.equals("/api/v1/auth/resend-code"))response(operation,"202",delivery); // data absent without authorized context
                if(path.equals("/api/v1/animals/capacity"))response(operation,"200",envelope(capacity));
                if(path.equals("/api/v1/vet-links/{linkId}")&&verb.equals("DELETE"))response(operation,"200",envelope(revocation));
                if(path.equals("/api/v1/notifications"))response(operation,"200",envelope(notifications));
                if(!publicAuth||path.endsWith("/login")||path.endsWith("/refresh-token"))error(operation,"401","Credencial/sesión inválida o vencida: INVALID_CREDENTIALS, SESSION_INVALID o ACCESS_TOKEN_EXPIRED; login también puede informar EMAIL_NOT_VERIFIED o ACCOUNT_SUSPENDED");
                error(operation,"403","FORBIDDEN por permisos o STAGING_ACCESS_DENIED por barrera de staging");
                if(operation.getRequestBody()!=null||operation.getParameters()!=null)error(operation,"400","VALIDATION_ERROR: cuerpo/parámetros inválidos; ver details. Recuperación: RECOVERY_CODE_INVALID si el código no es utilizable.");
                if(path.contains("{")||(path.startsWith("/api/v1/subscription")&&!path.endsWith("/plans"))||path.startsWith("/api/v1/visits")||path.startsWith("/api/v1/attentions"))error(operation,"404","NOT_FOUND: recurso referenciado inexistente");
                if(!verb.equals("GET")&&!publicAuth)error(operation,"409","Conflicto de estado, integridad o concurrencia: INVALID_STATE_TRANSITION, ALREADY_EXISTS, INVALID_REFERENCE, CONCURRENT_UPDATE; en animales ANIMAL_VERSION_CONFLICT/DUPLICATE_ANIMAL_CODE; en clínica ATTENTION_REQUIRED/ATTENTION_ALREADY_EXISTS cuando corresponda.");
                if(path.equals("/api/v1/subscription/my-subscription"))error(operation,"409","CONCURRENT_UPDATE durante propagación del vencimiento: volver a consultar");
                if(!verb.equals("GET")&&(path.startsWith("/api/v1/animals")||path.startsWith("/api/v1/invitations")||path.startsWith("/api/v1/vet-links")||path.startsWith("/api/v1/attentions")||path.startsWith("/api/v1/visits")||path.startsWith("/api/v1/subscription")))error(operation,"422","Regla de negocio no satisfecha: elegibilidad, cupo, perfil, plan o transición. Consultar errorCode, no inferir datos privados.");
                if(path.equals("/api/v1/auth/register"))error(operation,"409","EMAIL_ALREADY_REGISTERED: correo ya registrado");
                if(path.equals("/api/v1/auth/verify"))error(operation,"422","INVALID_CODE; con credenciales de contexto válidas también CODE_EXPIRED o CODE_ATTEMPTS_EXCEEDED. Sin ese contexto, error genérico.");
                if(path.equals("/api/v1/auth/resend-code"))error(operation,"409","VERIFICATION_NOT_PENDING: la cuenta dejó de estar pendiente durante una operación autorizada");
                if(path.equals("/api/v1/auth/verify")||path.equals("/api/v1/auth/resend-code")||path.contains("password-recovery")){
                    error(operation,"429","Límite de intentos/reenvíos/origen. Respuestas públicas no enumeran cuentas.");
                    operation.getResponses().get("429").addHeaderObject("Retry-After",new Header().description("Segundos mínimos de espera").schema(new IntegerSchema()));
                }
                if(!publicAuth)error(operation,"503","AUTHENTICATION_UNAVAILABLE: no se pudo comprobar la sesión; las rutas de simulación también devuelven PAYMENTS_UNAVAILABLE si no están autorizadas");
                if(path.equals("/api/v1/auth/register")||path.equals("/api/v1/auth/resend-code"))error(operation,"503","EMAIL_UNAVAILABLE: no se pudo preparar el trabajo de correo; la transacción revierte");
                if(path.contains("password-recovery"))error(operation,"503","RECOVERY_UNAVAILABLE: recuperación deshabilitada o temporalmente indisponible");
                if(path.endsWith("/refresh-token"))error(operation,"503","REFRESH_CONFIRMATION_PENDING: reintentar con el mismo refreshToken y operationId");
                error(operation,"500","INTERNAL_ERROR: fallo inesperado sanitizado");
            }));
            api.getComponents().getSchemas().forEach((name,schema)->{
                if(name.startsWith("ApiResponse"))schema.setRequired(List.of("success","message","timestamp"));
                if(schema.getProperties()==null)return;
                schema.getProperties().forEach((property,value)->{
                    Schema<?> field=(Schema<?>)value;
                    if(List.of("password","newPassword","refreshToken").contains(property)&&name.endsWith("Request"))field.setWriteOnly(true);
                    if(property.equals("code")&&(name.equals("VerifyRequest")||name.equals("PasswordRecoveryConfirm"))){field.setPattern("^[0-9]{6}$");field.setMinLength(6);field.setMaxLength(6);}
                    if(property.equals("expiresIn"))field.setDescription("Vida restante del access token en segundos, limitada por la sesión");
                    if(property.equals("email")||property.equals("vetEmail"))field.setFormat("email");
                    if(name.endsWith("Request")&&(schema.getRequired()==null||!schema.getRequired().contains(property)))field.setNullable(true);
                });
            });
            enumProperty(api,"VisitStatusRequest","status",List.of("COMPLETADA","CANCELADA"));
            enumProperty(api,"PlanView","type",List.of("GRATUITO","PREMIUM"));
            enumProperty(api,"SubscriptionView","planType",List.of("GRATUITO","PREMIUM"));
            enumProperty(api,"SubscriptionView","status",List.of("ACTIVA","VENCIDA","CANCELADA"));
            enumProperty(api,"PaymentResultView","paymentStatus",List.of("PENDING","APPROVED","DECLINED"));
            enumProperty(api,"CheckoutResult","status",List.of("PENDING"));
            enumProperty(api,"RegisterResult","emailDelivery",List.of("UNCONFIRMED"));
            enumProperty(api,"EmailDelivery","emailDelivery",List.of("UNCONFIRMED"));
            enumProperty(api,"InvitationResponse","emailDelivery",List.of("UNCONFIRMED","SENT"));
            nullable(api,"AccountProfile","phone","photoUrl","address","dni");
            nullable(api,"AnimalDetailResponse","name","breed","birthDate","photoUrl");
            nullable(api,"AnimalListItem","name");
            nullable(api,"FarmResponse","location");
            nullable(api,"AppointmentView","animalName","reason");
            nullable(api,"VisitResponse","reason");
            nullable(api,"AttentionDetail","instructions");
            nullable(api,"SubscriptionView","endsAt");
            nullable(api,"PaymentResultView","endsAt");
            nullable(api,"LinkView","revokedAt");
            var refresh=api.getComponents().getSchemas().get("RefreshRequest");
            ((Schema<?>)refresh.getProperties().get("operationId")).setPattern("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
            var confirm=api.getComponents().getSchemas().get("PasswordRecoveryConfirm");
            confirm.setRequired(List.of("email","code","newPassword"));
            ((Schema<?>)confirm.getProperties().get("newPassword")).minLength(8).pattern("^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9\\s]).{8,}$").writeOnly(true);
        };
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void enumProperty(OpenAPI api,String name,String property,List<String> values){Schema schema=(Schema)api.getComponents().getSchemas().get(name).getProperties().get(property);schema.setEnum(values);}
    private static void nullable(OpenAPI api,String name,String... properties){var schema=api.getComponents().getSchemas().get(name);for(var property:properties)if(schema.getProperties().get(property) instanceof Schema<?> field)field.setNullable(true);}
    private static Schema<?> model(OpenAPI api,java.lang.reflect.Type type){var resolved=ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));api.getComponents().getSchemas().putAll(resolved.referencedSchemas);return resolved.schema;}
    private static Schema<?> envelope(Schema<?> data){var out=new ObjectSchema().addProperty("success",new BooleanSchema()._enum(List.of(true))).addProperty("message",new StringSchema()).addProperty("timestamp",new DateTimeSchema()).required(List.of("success","message","timestamp"));if(data!=null)out.addProperty("data",data);return out;}
    private static void response(Operation o,String status,Schema<?> schema){o.getResponses().get(status).content(new Content().addMediaType("application/json",new MediaType().schema(schema)));}
    private static void error(Operation o,String status,String description){o.getResponses().addApiResponse(status,new ApiResponse().description(description).content(new Content().addMediaType("application/json",new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")))));}
    private static String success(String verb,String path){
        if(path.equals("/api/v1/auth/password-recovery/confirm"))return "204";
        if(path.equals("/api/v1/auth/resend-code")||path.equals("/api/v1/auth/password-recovery/request"))return "202";
        if(verb.equals("POST")&&(Set.of("/api/v1/auth/register","/api/v1/farms","/api/v1/animals","/api/v1/invitations","/api/v1/visits/schedule","/api/v1/visits/follow-up","/api/v1/attentions").contains(path)||path.endsWith("/observations")||path.endsWith("/instructions")))return "201";
        return "200";
    }
    private static List<String> roles(String verb,String path){
        if(path.startsWith("/api/v1/auth/"))return List.of();
        if(path.startsWith("/api/v1/visits")||(path.startsWith("/api/v1/attentions")&&!verb.equals("GET"))||path.equals("/api/v1/invitations/pending")||path.endsWith("/respond")||path.equals("/api/v1/vet-links/my-farmers")||path.equals("/api/v1/vet-links/capacity"))return List.of("VETERINARIO");
        if((path.startsWith("/api/v1/animals")&&(!verb.equals("GET")||path.endsWith("/capacity")))||(path.equals("/api/v1/farms")&&verb.equals("POST"))||(path.equals("/api/v1/invitations")&&verb.equals("POST"))||(path.startsWith("/api/v1/vet-links/")&&verb.equals("DELETE")))return List.of("GANADERO");
        return List.of("GANADERO","VETERINARIO");
    }
}
