package com.e9rental;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
class PublicOutputsTest {
    @Test void outputsAreDeterministicSyntheticAndEvidenceConsistent()throws Exception{
        Path first=Path.of("target/public-output-first"),second=Path.of("target/public-output-second");PublicOutputGenerator.generate(first);PublicOutputGenerator.generate(second);
        for(String name:List.of("whatsapp_output.json","gmail_output.json","drive_output.json")){
            assertThat(Files.readAllBytes(first.resolve(name))).isEqualTo(Files.readAllBytes(second.resolve(name)));var output=Fixtures.JSON.readTree(first.resolve(name).toFile());assertThat(output.path("provisional_schema").asBoolean()).isTrue();assertThat(output.path("fixture_provenance").asText()).isEqualTo("GENERATED_SYNTHETIC_FIXTURES");assertThat(Files.readString(first.resolve(name))).doesNotContain("M3_EXCLUDED_CANARY","M4_EXCLUDED_METADATA_CANARY","\"LIVE\"");
        }
        var g=Fixtures.JSON.readTree(first.resolve("gmail_output.json").toFile());assertThat(g.path("message_count").asInt()).isEqualTo(9);int unanswered=0,critical=0;for(var m:g.path("messages")){if(m.path("unanswered_five_days").asBoolean())unanswered++;for(var a:m.path("attachments"))if(a.path("business_critical").asBoolean()){critical++;assertThat(a.path("reasons").isEmpty()).isFalse();}}assertThat(g.path("unanswered_count").asInt()).isEqualTo(unanswered);assertThat(g.path("unanswered_messages").size()).isEqualTo(unanswered);assertThat(critical).isEqualTo(5);
        var d=Fixtures.JSON.readTree(first.resolve("drive_output.json").toFile());var categories=new HashSet<String>();for(var doc:d.path("documents"))categories.add(doc.path("category").asText());assertThat(categories).containsExactlyInAnyOrder("sales","marketing","verification","office","accounts");
        var w=Fixtures.JSON.readTree(first.resolve("whatsapp_output.json").toFile());assertThat(w.path("message_count").asInt()).isEqualTo(8);for(var group:w.path("groups")){assertThat(group.path("group_name").asText()).startsWith("Synthetic ");assertThat(group.path("participants").isEmpty()).isFalse();assertThat(group.path("summary").size()).isLessThanOrEqualTo(3);}
    }
}
