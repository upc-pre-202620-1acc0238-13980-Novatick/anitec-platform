package com.anitec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocumentationFilesTests {
    @Test void readmeAndNewGuidesHaveExistingRelativeLinks()throws Exception{
        var files=new ArrayList<Path>(List.of(Path.of("README.md"),Path.of("docs/mvp-closure-candidate.md")));
        try(var stream=Files.walk(Path.of("docs/api"))){stream.filter(p->p.toString().endsWith(".md")).forEach(files::add);}
        var pattern=Pattern.compile("\\[[^\\]]+\\]\\(([^)]+)\\)");
        for(var file:files){var matcher=pattern.matcher(Files.readString(file));while(matcher.find()){
            var target=matcher.group(1).split("#",2)[0];if(target.isBlank()||target.matches("^[a-zA-Z]+:.*"))continue;
            assertThat(Files.exists(file.toAbsolutePath().getParent().resolve(target).normalize())).as(file+" -> "+target).isTrue();
        }}
    }
    @Test void publicSiteHasOnlyReviewedStaticReferencesAndUnchangedVendorAssets()throws Exception{
        var root=Path.of("docs/api/site");var html=Files.readString(root.resolve("index.html"));
        var refs=Pattern.compile("(?:src|href)=\"([^\"]+)\"").matcher(html);
        while(refs.find()){String ref=refs.group(1);if(ref.startsWith("data:"))continue;assertThat(ref).doesNotContain("://");assertThat(Files.exists(root.resolve(ref))).isTrue();}
        var spec=new ObjectMapper().readTree(Files.readString(root.resolve("openapi-public.json")));
        assertThat(spec.path("servers").get(0).path("url").asText()).isEqualTo("https://api.example.invalid");
        spec.path("paths").fieldNames().forEachRemaining(path->assertThat(path).startsWith("/api/v1/").doesNotContain("/admin","/outbox","/actuator"));
        var manifest=new ObjectMapper().readTree(Files.readString(root.resolve("vendor/checksums.json")));var entries=manifest.fields();
        while(entries.hasNext()){var e=entries.next();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("vendor").resolve(e.getKey()))));assertThat(hash).as(e.getKey()).isEqualTo(e.getValue().asText());}
    }
}
