package com.company.triage.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FND-87. Pins the fold rule that keeps a real Sumo {@code _raw} from filling a ServiceNow
 * work note, and — more importantly — pins what the fold must never drop.
 *
 * <p>The tests are written against the SHAPE of a real trace rather than a synthetic one,
 * because the failure this class prevents is specifically "the shortening looked fine on a
 * 6-line example and ate the root cause on a 200-line one".
 */
class StackTraceFoldTest {

    private static final String REAL_SHAPED_TRACE = String.join("\n",
            "2026-08-05 09:14:22.117 ERROR 1 --- [http-nio-8080-exec-4] c.c.payment.PaymentReconciler : Reconciliation failed for order ORD-99213",
            "java.lang.IllegalStateException: Ledger entry missing for settlement batch SB-4471",
            "\tat com.company.payment.PaymentReconciler.reconcile(PaymentReconciler.java:142)",
            "\tat com.company.payment.PaymentReconciler.onBatch(PaymentReconciler.java:88)",
            "\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)",
            "\tat java.base/java.lang.reflect.Method.invoke(Method.java:568)",
            "\tat org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:207)",
            "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1072)",
            "\tat org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:167)",
            "\tat org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1740)",
            "\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)",
            "\tat com.company.web.RequestIdFilter.doFilter(RequestIdFilter.java:31)",
            "\tat java.base/java.lang.Thread.run(Thread.java:840)",
            "Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms",
            "\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)",
            "\tat com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:100)",
            "\tat org.springframework.jdbc.datasource.DataSourceUtils.fetchConnection(DataSourceUtils.java:160)",
            "\tat com.company.payment.LedgerRepository.findBatch(LedgerRepository.java:57)",
            "\t... 27 more");

    /**
     * The root cause is at the BOTTOM of a Java trace and the throw site is at the TOP, which
     * is why "keep the last N lines" — the obvious shortening — is wrong here. Both ends
     * survive, or the note has been made shorter and useless.
     */
    @Test
    void keepsBothTheThrowSiteAndTheDeepestCause() {
        var folded = StackTraceFold.fold(REAL_SHAPED_TRACE, "");

        assertThat(folded.text())
                .contains("java.lang.IllegalStateException: Ledger entry missing")
                .contains("at com.company.payment.PaymentReconciler.reconcile(PaymentReconciler.java:142)")
                .contains("Caused by: java.sql.SQLTransientConnectionException")
                .contains("at com.company.payment.LedgerRepository.findBatch(LedgerRepository.java:57)")
                .contains("... 27 more");
    }

    /** Every application frame survives — they name the file and line someone will open. */
    @Test
    void keepsEveryApplicationFrame() {
        var folded = StackTraceFold.fold(REAL_SHAPED_TRACE, "");

        assertThat(folded.text())
                .contains("com.company.payment.PaymentReconciler.onBatch")
                .contains("com.company.web.RequestIdFilter.doFilter")
                .contains("com.company.payment.LedgerRepository.findBatch");
    }

    @Test
    void elidesRunsOfFrameworkFramesAndSaysHowMany() {
        var folded = StackTraceFold.fold(REAL_SHAPED_TRACE, "");

        assertThat(folded.text())
                .doesNotContain("DispatcherServlet")
                .doesNotContain("StandardWrapperValve")
                .containsPattern("… \\d+ framework frames elided \\(");
        assertThat(folded.shortened()).isTrue();
        assertThat(folded.keptLines()).isLessThan(folded.originalLines());
    }

    /**
     * The disclosure is the whole reason this is safe to do to a customer-visible note: a
     * reader with no expander must be able to tell that lines were removed.
     */
    @Test
    void reportsTheAbbreviationInWords() {
        var folded = StackTraceFold.fold(REAL_SHAPED_TRACE, "");

        assertThat(StackTraceFold.summarise(folded))
                .contains("stack trace abbreviated")
                .contains("full text in Sumo Logic")
                .contains(String.valueOf(folded.elidedLines()));
    }

    /** Ordinary evidence is most of the note and must not grow markers it never needed. */
    @Test
    void leavesNonStackTraceEvidenceExactlyAsItWas() {
        String plain = "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471";

        var folded = StackTraceFold.fold(plain, "  ");

        assertThat(folded.text()).isEqualTo(plain);
        assertThat(folded.shortened()).isFalse();
        assertThat(StackTraceFold.summarise(folded)).isNull();
    }

    /**
     * Frame elision alone does not bound the output — an all-application trace has no
     * framework runs to elide. The cap keeps head and tail so the deepest cause still lands.
     */
    @Test
    void capsATraceThatElisionAloneCannotShorten() {
        StringBuilder huge = new StringBuilder("java.lang.RuntimeException: deep\n");
        for (int i = 0; i < 200; i++) {
            huge.append("\tat com.company.svc.Layer").append(i).append(".call(Layer.java:").append(i).append(")\n");
        }
        huge.append("Caused by: java.io.IOException: the actual root cause");

        var folded = StackTraceFold.fold(huge.toString(), "");

        assertThat(folded.text().split("\n")).hasSizeLessThanOrEqualTo(40);
        assertThat(folded.text())
                .contains("java.lang.RuntimeException: deep")
                .contains("at com.company.svc.Layer0.call")
                .contains("Caused by: java.io.IOException: the actual root cause")
                .contains("further lines elided");
    }

    /** A serialised payload dumped into a message line has no structure to fold along. */
    @Test
    void clipsOnePathologicallyLongLine() {
        String trace = "java.lang.RuntimeException: " + "x".repeat(5000)
                + "\n\tat com.company.a.B.c(B.java:1)";

        var folded = StackTraceFold.fold(trace, "");

        assertThat(folded.text().split("\n")[0]).hasSizeLessThan(500).endsWith("…");
    }

    @Test
    void indentsContinuationLinesSoTheNoteStaysABulletList() {
        var folded = StackTraceFold.fold(REAL_SHAPED_TRACE, "  ");

        assertThat(folded.text().lines().skip(1))
                .isNotEmpty()
                .allMatch(l -> l.startsWith("  "));
    }
}
