package com.e9rental;

import com.e9rental.connector.GmailSearchPlan;
import com.e9rental.connector.GmailGateway;
import com.e9rental.service.qa.GmailQuestionRetrievalService;
import com.e9rental.service.qa.QuestionInterpreter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GmailTypedSearchPlanTest {
    private static final String QUESTION=
        "Find Gmail emails from recruiter@example.com about interview between 2026-10-01 and 2026-10-18.";

    @Test void parsesExactSenderTopicAndExplicitInclusiveDateRange() {
        var interpreted=new QuestionInterpreter().interpret(QUESTION);
        var plan=GmailQuestionRetrievalService.buildSearchPlan(interpreted);
        assertThat(plan.senderEmail()).isEqualTo("recruiter@example.com");
        assertThat(plan.topicKeywords()).containsExactly("interview");
        assertThat(plan.startDateInclusive()).isEqualTo(LocalDate.of(2026,10,1));
        assertThat(plan.endDateInclusive()).isEqualTo(LocalDate.of(2026,10,18));
        assertThat(plan.scope()).isEqualTo(GmailSearchPlan.MailScope.REGULAR_MAIL);
        assertThat(interpreted.from()).isEqualTo(Instant.parse("2026-09-30T18:30:00Z"));
        assertThat(interpreted.to()).isEqualTo(Instant.parse("2026-10-18T18:29:59.999999999Z"));
    }

    @Test void rendersSafeGmailQueryWithKolkataStartAndExclusiveNextMidnightEnd() {
        var plan=GmailQuestionRetrievalService.buildSearchPlan(new QuestionInterpreter().interpret(QUESTION));
        long start=LocalDate.of(2026,10,1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toEpochSecond();
        long afterEnd=LocalDate.of(2026,10,19).atStartOfDay(ZoneId.of("Asia/Kolkata")).toEpochSecond();
        assertThat(GmailGateway.buildQuery(plan)).isEqualTo(
            "from:recruiter@example.com \"interview\" after:"+(start-1)+" before:"+afterEnd);
        assertThat(GmailGateway.buildQuery(plan)).doesNotContain("in:anywhere","OR","{","}");
    }

    @Test void supportsOneToThreeValidatedTopicWordsWithDefaultRegularMailScope() {
        var plan=GmailQuestionRetrievalService.buildSearchPlan(new QuestionInterpreter()
            .interpret("Find Gmail emails from recruiter@example.com about job application interview between 2026-10-01 and 2026-10-18."));
        assertThat(plan.topicKeywords()).containsExactly("job","application","interview");
        assertThat(plan.scope()).isEqualTo(GmailSearchPlan.MailScope.REGULAR_MAIL);
        var topicOnly=GmailQuestionRetrievalService.buildSearchPlan(new QuestionInterpreter()
            .interpret("Show Gmail records about interview"));
        assertThat(topicOnly.senderEmail()).isNull();
        assertThat(topicOnly.startDateInclusive()).isNull();
        assertThat(topicOnly.scope()).isEqualTo(GmailSearchPlan.MailScope.REGULAR_MAIL);
    }

    @Test void supportsTheSuggestedSimpleFindGmailTopicQuestion() {
        var plan=GmailQuestionRetrievalService.buildSearchPlan(new QuestionInterpreter()
            .interpret("Find Gmail emails about invoice"));

        assertThat(plan.queryKind()).isEqualTo(GmailSearchPlan.QueryKind.TOPIC);
        assertThat(plan.topicKeywords()).containsExactly("invoice");
        assertThat(plan.maxMessages()).isEqualTo(20);
        assertThat(plan.scope()).isEqualTo(GmailSearchPlan.MailScope.REGULAR_MAIL);
        assertThat(GmailGateway.buildQuery(plan)).isEqualTo("\"invoice\"");
    }

    @Test void supportsGoogleAsTheTopicInTheExactSimpleFindGmailQuestion() {
        var plan=GmailQuestionRetrievalService.buildSearchPlan(new QuestionInterpreter()
            .interpret("Find Gmail emails about Google"));

        assertThat(plan.queryKind()).isEqualTo(GmailSearchPlan.QueryKind.TOPIC);
        assertThat(plan.topicKeywords()).containsExactly("google");
        assertThat(plan.maxMessages()).isEqualTo(20);
        assertThat(plan.scope()).isEqualTo(GmailSearchPlan.MailScope.REGULAR_MAIL);
        assertThat(GmailGateway.buildQuery(plan)).isEqualTo("\"google\"");
    }

    @Test void rejectsUnsupportedFindStyleQuestionsAndCrossSourceQueries() {
        var interpreter=new QuestionInterpreter();
        for(String question:List.of(
                "Find Drive files about invoice",
                "Find Gmail emails about invoice in Drive",
                "Find Gmail emails about Google Drive",
                "Find Gmail emails about the",
                "Find Gmail emails about invoice contracts rent estimate",
                "Find Gmail emails from recruiter@example.com about invoice")) {
            var plan=interpreter.interpret(question);
            assertThatThrownBy(()->GmailQuestionRetrievalService.buildSearchPlan(plan))
                .as(question).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void rejectsInvalidOrReversedDatesBeforeProviderAccess() {
        var interpreter=new QuestionInterpreter();
        assertThatThrownBy(()->interpreter.interpret(
            "Find Gmail emails from recruiter@example.com about interview between 2026-02-30 and 2026-03-01."))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->interpreter.interpret(
            "Find Gmail emails from recruiter@example.com about interview between 2026-10-18 and 2026-10-01."))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsAmbiguousSenderAndQueryInjection() {
        var interpreter=new QuestionInterpreter();
        var ambiguous=interpreter.interpret(
            "Find Gmail emails from recruiter at example.com about interview between 2026-10-01 and 2026-10-18.");
        assertThat(ambiguous.unsupported()).isTrue();
        assertThatThrownBy(()->GmailQuestionRetrievalService.buildSearchPlan(ambiguous)).isInstanceOf(RuntimeException.class);
        var injected=interpreter.interpret(
            "Find Gmail emails from recruiter@example.com about interview OR in:anywhere between 2026-10-01 and 2026-10-18.");
        assertThat(injected.unsupported()).isTrue();
        assertThatThrownBy(()->GmailQuestionRetrievalService.buildSearchPlan(injected)).isInstanceOf(RuntimeException.class);
    }

    @Test void exactSenderPlanCannotCarryOperatorsOrUnsupportedScope() {
        assertThatThrownBy(()->new GmailSearchPlan("recruiter@example.com",List.of("in:anywhere"),
            LocalDate.of(2026,10,1),LocalDate.of(2026,10,18),GmailSearchPlan.MailScope.REGULAR_MAIL))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void relativeDateSearchUsesAsiaKolkataDayAndTwentyMessageBound() {
        var clock=Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"),ZoneOffset.UTC);
        var plan=new QuestionInterpreter(clock).interpret("Show my Gmail emails from yesterday.");
        var search=GmailQuestionRetrievalService.buildSearchPlan(plan);
        assertThat(search.queryKind()).isEqualTo(GmailSearchPlan.QueryKind.DATE);
        assertThat(search.maxMessages()).isEqualTo(20);
        assertThat(search.startDateInclusive()).isEqualTo(LocalDate.of(2026,10,18));
        assertThat(plan.from()).isEqualTo(Instant.parse("2026-10-17T18:30:00Z"));
        assertThat(plan.to()).isEqualTo(Instant.parse("2026-10-18T18:29:59.999999999Z"));
        assertThat(GmailGateway.buildQuery(search)).contains("after:","before:").doesNotContain("in:anywhere");
    }

    @Test void todayDateQuestionIncludesTheCurrentKolkataDate() {
        var clock=Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"),ZoneOffset.UTC);
        var plan=new QuestionInterpreter(clock).interpret("Show my Gmail emails from today.");
        var search=GmailQuestionRetrievalService.buildSearchPlan(plan);
        assertThat(search.startDateInclusive()).isEqualTo(LocalDate.of(2026,10,19));
        assertThat(plan.from()).isEqualTo(Instant.parse("2026-10-18T18:30:00Z"));
        assertThat(plan.to()).isEqualTo(Instant.parse("2026-10-19T18:29:59.999999999Z"));
    }

    @Test void latestTenQuestionHasAnEmptySafeQueryAndTenMessageLimit() {
        var plan=new QuestionInterpreter().interpret("Show my latest 10 Gmail emails.");
        var search=GmailQuestionRetrievalService.buildSearchPlan(plan);
        assertThat(search.queryKind()).isEqualTo(GmailSearchPlan.QueryKind.RECENT);
        assertThat(search.maxMessages()).isEqualTo(10);
        assertThat(search.topicKeywords()).isEmpty();
        assertThat(GmailGateway.buildQuery(search)).isEmpty();
    }

    @Test void ambiguousRelativeDateAndLatestRequestsRemainUnsupported() {
        var interpreter=new QuestionInterpreter();
        for(String question:List.of("What important emails did I receive today?", "Show all emails from yesterday",
                "Show my latest emails", "Show my latest 10 Gmail emails from yesterday", "Summarize my emails from yesterday")) {
            var plan=interpreter.interpret(question);
            assertThatThrownBy(()->GmailQuestionRetrievalService.buildSearchPlan(plan)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void relativeDayCalculationHandlesMonthYearAndLeapYearBoundaries() {
        record Case(String now,String phrase,LocalDate expected) {}
        for(var c:List.of(new Case("2026-03-01T02:00:00Z","yesterday",LocalDate.of(2026,2,28)),
                new Case("2024-03-01T02:00:00Z","yesterday",LocalDate.of(2024,2,29)),
                new Case("2026-01-01T02:00:00Z","yesterday",LocalDate.of(2025,12,31)),
                new Case("2026-12-31T17:00:00Z","today",LocalDate.of(2026,12,31)))) {
            var interpreter=new QuestionInterpreter(Clock.fixed(Instant.parse(c.now()),ZoneOffset.UTC));
            var plan=interpreter.interpret("Show my Gmail emails from "+c.phrase+".");
            assertThat(plan.gmailSearch().startDateInclusive()).isEqualTo(c.expected());
            assertThat(plan.from()).isEqualTo(c.expected().atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant());
            assertThat(plan.to()).isEqualTo(c.expected().plusDays(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant().minusNanos(1));
        }
    }

    @Test void todayChangesAtTheAsiaKolkataMidnightBoundary() {
        var beforeMidnight=new QuestionInterpreter(Clock.fixed(Instant.parse("2026-10-18T18:29:59.999Z"),ZoneOffset.UTC))
            .interpret("Show my Gmail emails from today");
        var afterMidnight=new QuestionInterpreter(Clock.fixed(Instant.parse("2026-10-18T18:30:00Z"),ZoneOffset.UTC))
            .interpret("Show my Gmail emails from today");
        assertThat(beforeMidnight.gmailSearch().startDateInclusive()).isEqualTo(LocalDate.of(2026,10,18));
        assertThat(afterMidnight.gmailSearch().startDateInclusive()).isEqualTo(LocalDate.of(2026,10,19));
        assertThat(beforeMidnight.to().plusNanos(1)).isEqualTo(afterMidnight.from());
    }
}
