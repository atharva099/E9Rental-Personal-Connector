package com.e9rental;

import com.e9rental.connector.GmailGateway;
import com.e9rental.connector.GmailSearchPlan;
import com.e9rental.model.GmailSearchFilters;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GmailSearchFiltersTest {
    @Test void buildsTypedPlanForKeywordSenderAndInclusiveKolkataDateRange() {
        var plan=new GmailSearchFilters(" Interview hiring ","Recruiter@example.com","2026-10-01","2026-10-18").toPlan();
        assertThat(plan.queryKind()).isEqualTo(GmailSearchPlan.QueryKind.DIRECT_SEARCH);
        assertThat(plan.topicKeywords()).containsExactly("interview","hiring");
        assertThat(plan.senderEmail()).isEqualTo("recruiter@example.com");
        assertThat(plan.scope()).isEqualTo(GmailSearchPlan.MailScope.REGULAR_MAIL);
        assertThat(plan.maxMessages()).isEqualTo(20);
        long localStart=LocalDate.of(2026,10,1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toEpochSecond();
        long nextMidnight=LocalDate.of(2026,10,19).atStartOfDay(ZoneId.of("Asia/Kolkata")).toEpochSecond();
        assertThat(GmailGateway.buildQuery(plan)).isEqualTo(
            "from:recruiter@example.com \"interview\" \"hiring\" after:"+(localStart-1)+" before:"+nextMidnight);
        assertThat(GmailGateway.buildQuery(plan)).doesNotContain("OR","in:anywhere","{","}");
    }

    @Test void permitsAnyNonemptySafeFilterCombinationButCapsDateRangeTo366Days() {
        assertThat(new GmailSearchFilters("invoice",null,null,null).toPlan().topicKeywords()).containsExactly("invoice");
        assertThat(new GmailSearchFilters(null,"a@example.com",null,null).toPlan().senderEmail()).isEqualTo("a@example.com");
        assertThat(new GmailSearchFilters(null,null,"2026-01-01","2026-01-01").toPlan().startDateInclusive())
            .isEqualTo(LocalDate.of(2026,1,1));
        assertThat(new GmailSearchFilters(null,"a@example.com","2026-01-01","2026-12-31").toPlan().senderEmail())
            .isEqualTo("a@example.com");
        assertThatThrownBy(()->new GmailSearchFilters(null,null,"2026-01-01","2027-01-02").toPlan())
            .isInstanceOf(RuntimeException.class);
    }

    @Test void rejectsMissingFiltersUnsupportedCharactersAndTooManyKeywords() {
        for(var filters:List.of(new GmailSearchFilters(null,null,null,null),
                new GmailSearchFilters("interview OR in:anywhere",null,null,null),
                new GmailSearchFilters("invoice*",null,null,null),new GmailSearchFilters("one two three four",null,null,null),
                new GmailSearchFilters("a".repeat(41),null,null,null)))
            assertThatThrownBy(filters::toPlan).isInstanceOf(RuntimeException.class);
    }

    @Test void rejectsInvalidSenderAndMalformedIncompleteOrReversedDates() {
        for(var filters:List.of(new GmailSearchFilters("invoice","a@example.com OR from:b@example.com",null,null),
                new GmailSearchFilters("invoice",null,"2026-02-30","2026-03-01"),
                new GmailSearchFilters("invoice",null,"2026-10-01",null),
                new GmailSearchFilters("invoice",null,"2026-10-18","2026-10-01"),
                new GmailSearchFilters("invoice",null,"2026-1-01","2026-10-02")))
            assertThatThrownBy(filters::toPlan).isInstanceOf(RuntimeException.class);
    }

    @Test void rejectsMaximumDateBecauseExclusiveNextDayCannotBeRepresented() {
        assertThatThrownBy(()->new GmailSearchFilters("invoice",null,"9999-12-31","9999-12-31").toPlan())
            .isInstanceOf(com.e9rental.connector.GmailFailure.class)
            .hasMessage("Invalid Gmail search filters");
    }
}
