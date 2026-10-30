package com.e9rental;
import com.e9rental.service.DriveClassifier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DriveClassifierTest {
    DriveClassifier classifier=new DriveClassifier();
    @Test void producesExactlyOneAllowedCategoryAndReason() {
        String[][] samples={{"Quotation","sales"},{"Campaign brochure","marketing"},{"KYC passport","verification"},{"Meeting agenda","office"},{"Invoice payment","accounts"}};
        for(var sample:samples) {
            var result=classifier.classify(sample[0],"");
            assertThat(result.category()).isEqualTo(sample[1]);assertThat(result.reason()).isNotBlank();
        }
    }
    @Test void precedenceResolvesAmbiguousDocumentsDeterministically() {
        assertThat(classifier.classify("Invoice quotation","KYC").category()).isEqualTo("verification");
        assertThat(classifier.classify("Invoice quotation marketing","").category()).isEqualTo("accounts");
        assertThat(classifier.classify("Quotation brochure","").category()).isEqualTo("sales");
    }
    @Test void matchesWholeWordsAndUsesOfficeFallback() {
        assertThat(classifier.classify("taxidermy","").category()).isEqualTo("office");
        assertThat(classifier.classify("","").category()).isEqualTo("office");
    }
}
