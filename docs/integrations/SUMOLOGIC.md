# Sumo Logic — getting credentials

Used by: real Sumo Logic gateway (`triage.connectors.sumo=real`).
Fills `secrets.properties` keys `triage.integrations.sumo.{base-url,user,secret}`
(user = Access ID, secret = Access Key).

Auth is an Access ID / Access Key pair (used as HTTP Basic credentials) against the
Sumo Logic Search Job API.

## 1. Create an Access Key

1. Log in to Sumo Logic.
2. Go to your **user avatar** (top right) → **Preferences**, or directly to
   **Administration** → **Security** → **Access Keys** if you have admin rights.
3. **Add Access Key** → give it a name (e.g. `triagemate-demo`) → **Create**.
4. Copy both the **Access ID** and **Access Key** immediately — the key is shown once.

Prefer creating the key under a **role scoped to the log sources the app will read**
rather than a full-admin account. The app composes its `_sourceCategory` from
`triage.sumo.source-category-pattern` + the allowlisted `triage.sumo.allowed-environments`
(see `src/main/resources/application.yml`); scope the role to match that pattern.
(FND-70, corrected 2026-08-05 — this previously cited `triage.sumo.allowed-scopes` and the
fixture values `prod/payment` / `prod/order-api`, none of which exist any more.)

## 2. Find your API endpoint (base URL)

Sumo Logic's API endpoint depends on which deployment/region your org is on — it is
**not** the same as your login URL. Check:
**Administration** → **Account** → **Account Overview**, which lists your deployment
(e.g. `us1`, `eu`, `au`) and the matching API endpoint, typically
`https://api.<deployment>.sumologic.com`.

## 3. Confirm access

```bash
curl -u <access-id>:<access-key> \
  "https://api.us1.sumologic.com/api/v1/collectors" 
```

A `200` with a JSON collector list confirms the credentials work (endpoint may differ
per your deployment from step 2).

## 4. Fill `secrets.properties`

```properties
triage.integrations.sumo.base-url=https://api.us1.sumologic.com
triage.integrations.sumo.user=<access ID from step 1>
triage.integrations.sumo.secret=<access key from step 1>
```

## Notes

- The app only issues **search queries** (Search Job API) — it never modifies
  collectors, sources, or dashboards. A read-only/search-scoped role is sufficient.
- Access keys can be revoked individually from the same Access Keys page without
  affecting your login credentials.

## 5. Query shape (important)

The app composes the query from `triage.sumo.source-category-pattern` + `index`
(`application.yml`), producing:

```
_sourceCategory=IDT/ITServices/Tomcat/<project>/<env>/AppEvt_<project> and _index=Global_Standard_Infrequent
```

**Both clauses matter.** Without `_index` the corporate instance returns **zero rows** for
a query that is otherwise perfectly well-formed — which reads as "no logs for this
incident" rather than as a broken query. Pinned by `LogSearchRequestQueryTest`.

Timestamps go as second-precision UTC with no offset (`2026-08-03T04:16:40`), paired with
`"timeZone":"UTC"`. `OffsetDateTime.toString()` is rejected with
`400 searchjob.invalid.timestamp.from`. Pinned by `RealSumoGatewayTimeFormatTest`.

## 6. Verifying against the live API

`RealSumoGatewayLiveTest` hits the real API. It is **opt-in**: with no
`triage.integrations.sumo.*` values in `secrets.properties` it skips, so `mvn test` stays
green and offline on a machine without credentials.

```bash
mvn test -Dtest=RealSumoGatewayLiveTest
```

The project/environment it probes is **injectable** — the target is only a means to reach
the API, so it isn't baked in. Resolution order: system property → `secrets.properties` →
default (`delivery-hazards` / `ptest`).

```bash
mvn test -Dtest=RealSumoGatewayLiveTest \
    -Dsumo.probe.project=my-app -Dsumo.probe.environment=prod
```

or, to set it once per machine, in `secrets.properties` (gitignored, alongside the
credentials):

```properties
sumo.probe.project=my-app
sumo.probe.environment=prod
```

If the default probe project ever stops logging, the failure message names it and tells
you which flags to override — the test fails loudly rather than silently testing nothing.

Measured on the AU instance (2026-08-03): a 30-minute window over one project completes in
~4s; a 24-hour window was still gathering at 24s. The app caps the window at
`max-window-minutes` (30), so it stays in the fast case.
