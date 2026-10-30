package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.service.*;
import com.e9rental.service.qa.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class DemoInvoiceRegressionTest {
    @TempDir Path dir;
    @Test void packagedDemoInvoiceSurvivesIngestionRecreationAndExactQuestionWithValidCitation() throws Exception {
        var output=Fixtures.demoGmail();
        assertThat(output.dataMode()).isEqualTo("SYNTHETIC");assertThat(output.messageCount()).isEqualTo(7);
        var policy=WhatsAppFixtures.policy();var path=dir.resolve("gmail.json");
        Fixtures.JSON.writeValue(path.toFile(),output);
        String url="jdbc:h2:file:"+dir.resolve("index").toString().replace('\\','/');
        var repository=new UnifiedRepository(url,Fixtures.JSON,policy);
        var intake=new UnifiedIngestionService(repository,null,policy,Fixtures.JSON,path.toString(),"unused");
        assertThat(intake.ingest("GMAIL").inserted()).isEqualTo(7);
        assertThat(intake.ingest("GMAIL").unchanged()).isEqualTo(7);
        repository=new UnifiedRepository(url,Fixtures.JSON,policy);
        var qa=new QuestionAnswerService(repository,new QuestionInterpreter(),new DeterministicEvidenceProvider());
        var answer=qa.ask("What is the rental invoice amount in Gmail?");
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");assertThat(answer.evidence()).hasSize(1);
        var e=answer.evidence().getFirst();
        var r=repository.search(new UnifiedRepository.Query("invoice","GMAIL",null,null,null,null,"SYNTHETIC",null,null,100,0))
            .stream().filter(row->row.source().externalId().equals(e.sourceId())).findFirst().orElseThrow();
        assertThat(e.sourceId()).isEqualTo("demo-invoice-1200");assertThat(e.excerpt()).isEqualTo("Rental invoice amount is USD 1200.");
        assertThat(e.documentId()).isEqualTo(r.source().documentId());assertThat(e.sourceVersion()).isEqualTo(r.source().sourceVersion());
        assertThat(e.locator()).isEqualTo(r.citation().locator());assertThat(e.excerpt()).isEqualTo(r.source().contentText().substring(e.startOffset(),e.endOffset()));
        assertThat(qa.ask("What is the spaceship invoice amount in Gmail?").status()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }
}
