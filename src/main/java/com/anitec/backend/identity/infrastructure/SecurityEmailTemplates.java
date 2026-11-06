package com.anitec.backend.identity.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;

/** Language is selected per operation; the immutable outbox payload preserves it across retries. */
public final class SecurityEmailTemplates {
    private SecurityEmailTemplates() {}
    public static String payload(ObjectMapper json,String from,String to,String purpose,String code,String language) {
        if(!code.matches("[0-9]{6}")) throw new IllegalArgumentException("Invalid security code format");
        boolean en="en".equals(language), recovery="PASSWORD_RECOVERY".equals(purpose);
        if(!recovery && !"EMAIL_VERIFICATION".equals(purpose)) throw new IllegalArgumentException("Invalid email purpose");
        String title=en?(recovery?"Reset your password":"Verify your email"):(recovery?"Restablecer contraseña":"Verificar correo");
        String text=en?"Your ANITEC code to "+(recovery?"reset your password":"verify your email")+" is: "+code+
                "\nValid for 10 minutes from issuance. Ignore this email if you did not request it.":
                "Tu código de ANITEC para "+(recovery?"restablecer tu contraseña":"verificar tu correo")+" es: "+code+
                "\nVálido durante 10 minutos desde su emisión. Ignora este correo si no lo solicitaste.";
        String html="<!doctype html><html lang=\""+(en?"en":"es")+"\"><body><h1>ANITEC — "+escape(title)+
                "</h1><p>"+escape(text).replace("\n","</p><p>")+"</p></body></html>";
        var body=new LinkedHashMap<String,Object>();body.put("from",from);body.put("to",List.of(to));
        body.put("subject","ANITEC - "+title);body.put("html",html);body.put("text",text);
        try {return json.writeValueAsString(body);}catch(Exception invalid){throw new IllegalStateException("Email serialization unavailable");}
    }
    static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
