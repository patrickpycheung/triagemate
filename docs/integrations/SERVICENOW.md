# ServiceNow — getting credentials

Used by: `RealServiceNowGateway` (`src/main/java/com/company/triage/gateway/real/`).
Fills `secrets.properties` keys `triage.integrations.servicenow.{base-url,user,secret}`.

The app talks to ServiceNow's REST **Table API** over HTTP Basic auth. You need a
**dedicated service account**, not your personal login — personal accounts get
disabled/rotated and you don't want the demo depending on a human's password.

## 1. Pick / request a dev instance

Any ServiceNow instance works, but a **Personal Developer Instance (PDI)** is the
easiest path if you don't already have one:

1. Go to https://developer.servicenow.com/ and sign in (or create a free account).
2. **Manage** → **Instance** → **Request Instance** (or **Wake up instance** if you
   already have one — PDIs auto-hibernate after inactivity).
3. Note the instance URL, e.g. `https://devNNNNN.service-now.com` → this is
   `triage.integrations.servicenow.base-url`.

If you're using a corporate/team dev instance instead, ask your ServiceNow admin for
a **dev/test instance URL** — never point this at a production instance.

## 2. Create a service account

Personal Developer Instances come with a default admin user (`admin`) whose password
is shown once when the instance is provisioned — usable for a quick hackathon demo,
but prefer a scoped account if the instance is shared:

1. **System Security** → **Users and Groups** → **Users** → **New**.
2. Set a **User ID** (e.g. `svc-triagemate`) and a strong password.
3. Under **Roles**, grant the minimum needed:
   - `itil` — read/write access to `incident` records (covers `GET` + `PATCH` on
     `/api/now/table/incident`, which is all this app calls).
   - Avoid `admin` — the app never needs it.
4. Save. Log in once as that user via the UI to confirm the password works and there's
   no forced password-reset prompt blocking API auth.

## 3. Confirm REST API access

Table API is enabled by default on all ServiceNow instances. Sanity-check with curl:

```bash
curl -u svc-triagemate:<password> \
  "https://devNNNNN.service-now.com/api/now/table/incident?sysparm_limit=1"
```

A `200` with a JSON `result` array means you're good. A `401` means the credentials or
role are wrong; a `403` usually means the role lacks table access.

## 4. Fill `secrets.properties`

```bash
cp secrets.properties.example secrets.properties
```

```properties
triage.integrations.servicenow.base-url=https://devNNNNN.service-now.com
triage.integrations.servicenow.user=svc-triagemate
triage.integrations.servicenow.secret=<the password from step 2>
```

Then run with `-Dtriage.connectors.servicenow=real` — see the main [README](../../README.md) →
"Optional: post the comments to a REAL ServiceNow ticket".

## Notes

- The app only ever calls `GET` and `PATCH` on `incident` — it never deletes,
  reassigns, or closes tickets. `itil` role is sufficient; no elevated roles needed.
- Writes append to `work_notes` (internal) by default. Switch to `comments`
  (customer-facing) with `triage.servicenow.write-field=comments` if you want to see
  that path instead — but `work_notes` is safer for a demo against a shared instance.
- PDIs hibernate after ~1 hour idle. If the base URL suddenly returns connection
  errors, go wake it up from the developer portal.
