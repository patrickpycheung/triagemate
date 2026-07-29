# Sumo Logic — getting credentials

Used by: real Sumo Logic gateway (`triage.connectors.sumo=real`).
Fills `.env` vars `SUMO_BASE_URL`, `SUMO_ACCESS_ID`, `SUMO_ACCESS_KEY`.

Auth is an Access ID / Access Key pair (used as HTTP Basic credentials) against the
Sumo Logic Search Job API.

## 1. Create an Access Key

1. Log in to Sumo Logic.
2. Go to your **user avatar** (top right) → **Preferences**, or directly to
   **Administration** → **Security** → **Access Keys** if you have admin rights.
3. **Add Access Key** → give it a name (e.g. `triagemate-demo`) → **Create**.
4. Copy both the **Access ID** and **Access Key** immediately — the key is shown once.

Prefer creating the key under a **role scoped to the app's allowed log scopes**
(`prod/payment`, `prod/order-api` — see `triage.sumo.allowed-scopes` in
`src/main/resources/application.yml`) rather than a full-admin account.

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

## 4. Fill `.env`

```
SUMO_BASE_URL=https://api.us1.sumologic.com
SUMO_ACCESS_ID=<access ID from step 1>
SUMO_ACCESS_KEY=<access key from step 1>
```

## Notes

- The app only issues **search queries** (Search Job API) — it never modifies
  collectors, sources, or dashboards. A read-only/search-scoped role is sufficient.
- Access keys can be revoked individually from the same Access Keys page without
  affecting your login credentials.
