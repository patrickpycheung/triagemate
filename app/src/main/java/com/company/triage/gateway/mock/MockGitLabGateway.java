package com.company.triage.gateway.mock;

import com.company.triage.gateway.GitLabGateway;
import com.company.triage.model.CodeSearchResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mock GitLab (J7 dataset) — a snapshot of the seed-repo. Returns the emitting
 * source line for a given error token so the log↔code citation (RC3) resolves to
 * payment_service.py:44 (the distinctive-token line inside reconcile()).
 */
@Component
@Profile("mock")
public class MockGitLabGateway implements GitLabGateway {

    @Override
    public List<CodeSearchResult> searchCode(String project, String searchTerm) {
        String t = searchTerm == null ? "" : searchTerm.toUpperCase();
        if (t.contains("PAYMENT_RECONCILE_MISMATCH") || t.contains("RECONCILE")) {
            return List.of(new CodeSearchResult(
                    "order-payments/payment-service",
                    "payment_service.py",
                    44,
                    "logger.error(  # line 43\n    \"PAYMENT_RECONCILE_MISMATCH order=%s expected=%.2f charged=%.2f\",  # line 44\n    order[\"id\"], expected, charged,\n)\nraise ValueError(\"reconcile mismatch\")  # reconcile(): discount applied after tax upstream"));
        }
        return List.of();
    }
}
