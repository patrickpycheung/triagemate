package com.company.triage.model;

/** CMDB / service-ownership record for an application (J5, Step 4). */
public record ServiceOwnership(
        String application,
        String supportGroup,
        String businessService,
        String source
) {}
