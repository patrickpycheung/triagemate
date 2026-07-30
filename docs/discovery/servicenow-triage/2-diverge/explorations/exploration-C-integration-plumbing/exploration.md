# Detailed Integration Analysis

## 1. ServiceNow Trigger: Flow Designer vs Business Rule

### The Choice

**Recommendation: Flow Designer with REST outbound action** (not Business Rule + Outbound Message)

**Why not Business Rule?**
- Business Rules are low-level and intended for database-adjacent logic (before/after insert/update).
- For external integrations, ServiceNow's 2026 guidance explicitly favors Flow Designer over Business Rules wherever possible.
- Flow Designer offers built-in retry logic, error handling, and superior debugging (you can inspect every variable and payload).
- Business Rules require hand-coded REST logic, credential management, and timeout handling.

**Why Flow Designer?**
- REST action runs via IntegrationHub spokes (authentication, retries, error mapping out of box).
- Visual debugging—see the payload and response at each step.
- Flow can be triggered on "incident created" or "incident updated."
- Licensing caveat: requires IntegrationHub + Enterprise Pack Installer plugins. Check if available in your instance.

### Minimal Call Sequence

1. **Create incident** (via ServiceNow UI or API) — triggers the flow.
2. **Flow trigger fires** on `incident.created` event.
3. **Extract incident data** — sys_id, title, description, category, project_code.
4. **REST action sends webhook** to external app:
   ```
   POST /triage/incident
   {
     "sys_id": "INC0123456",
     "project_code": "PROJ-X",
     "description": "...",
     "short_description": "..."
   }
   ```
5. **Flow waits** for response (with timeout, e.g., 30 seconds).
6. Flow continues or error-handles based on response.

### Authentication & Configuration

- **Auth:** Use a Service Principal or Integration User with REST scopes (read_api, write_api for your incident table).
- **Endpoint:** Point the REST action to your external app's `/triage/incident` endpoint (HTTP basic auth or OAuth if available).
- **Timeout & retry:** Flow Designer lets you configure retries (default 3) and timeout (30s typical).

### Polling Alternative (No Flow Designer Needed)

If Flow Designer is not available (no IntegrationHub license):
- **External app polls** ServiceNow's Table API every 30 seconds for new incidents.
- **Query:** `GET /api/now/table/incident?sysparm_query=sys_created_on>javascript%3Ags.minutesAgoStart(30)&sysparm_limit=10`
- **Advantage:** no ServiceNow configuration needed beyond Table API access.
- **Disadvantage:** 30-second lag (not sub-5-minute); more network calls; higher cost if you have many incidents.
- **For demo:** polling works but less elegant; triggers may feel sluggish.

---

## 2. GitLab API: Fetch Files from Master

### Endpoint & Auth

**Endpoint:** `GET /projects/{id}/repository/files/{file_path}?ref=master`

**Response:** Base64-encoded content + metadata (size, sha256).

**Alternative (raw):** `GET /projects/{id}/repository/files/{file_path}/raw?ref=master` — returns unencoded content directly.

**Auth:** PAT (Personal Access Token) scoped to `api` or `read_api`.

```bash
curl --header "PRIVATE-TOKEN: <your_pat>" \
  "https://gitlab.example.com/api/v4/projects/13083/repository/files/src%2Fapp.py?ref=master"
```

### Minimal Call Sequence

1. **Resolve project ID** from project code (e.g., "PROJ-X" → GitLab project 13083).
   - Store a mapping file or query GitLab's `/projects?search=PROJ-X` endpoint.
2. **List files** (optional, if you don't know which files to fetch):
   ```
   GET /projects/{id}/repository/tree?ref=master&recursive=true&per_page=100
   ```
3. **Fetch single file:**
   ```
   GET /projects/{id}/repository/files/{file_path}/raw?ref=master
   ```
4. **Decode/parse** (if using the base64 endpoint, decode).

### Rate Limits & Constraints

- **Size limit:** 10 MB file limit; larger files hit rate limits (5 req/min for >10 MB).
- **Rate limit:** 600 requests per 10 minutes (GitLab's standard).
- **Timeout:** typical 10 seconds.
- **Authentication:** PAT must not expire; refresh token strategy needed for long-running tasks.

### Demo Recommendation

**LIVE** — GitLab's API is reliable and rate limits are generous for small files (typical source files <1 MB). Just pre-cache the project ID ↔ code mapping.

---

## 3. Sumo Logic Search Job API: Async Logs

### The Async Flow

Sumo Logic search is **always asynchronous**. You create a job, poll status, and fetch results in pages.

**Endpoint:** `POST /search/jobs` (create), `GET /search/jobs/{job_id}` (status), `GET /search/jobs/{job_id}/messages` (results).

**Auth:** Basic auth with Access ID / Access Key (not username/password).

```bash
curl -X POST "https://api.sumologic.com/api/v1/search/jobs" \
  --basic -u "${SUMO_ID}:${SUMO_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "_sourceCategory=incident AND error",
    "from": 1721620800000,
    "to": 1721707200000,
    "timeZone": "UTC"
  }'
```

### Minimal Call Sequence

1. **Create search job:**
   ```
   POST /search/jobs
   { "query": "...", "from": <ms>, "to": <ms>, "timeZone": "UTC" }
   → Returns { "id": "ABC123" }
   ```
2. **Poll status** (every 1–2 seconds):
   ```
   GET /search/jobs/ABC123
   → Returns { "state": "GATHERING RESULTS" | "DONE GATHERING RESULTS" }
   ```
3. **Fetch messages** (once state is DONE):
   ```
   GET /search/jobs/ABC123/messages?offset=0&limit=100
   → Returns { "messages": [...] }
   ```
4. **Delete job** (cleanup):
   ```
   DELETE /search/jobs/ABC123
   ```

### Rate Limits & Constraints

- **Hard limit:** 4 requests per second (240/min) per access key; 10 concurrent requests per key.
- **Query timeout:** 8 hours max query runtime (even if you keep polling).
- **Result cap:** 100K messages per Flex License; larger queries must be split into time windows.
- **Regional endpoint:** US1, EU, AU—choose the right region or requests fail.

### Demo Recommendation

**MOCK** — Sumo Logic's rate limit (4 req/s) and polling latency (2–10 seconds) make it fragile for a demo. Pre-seed with a real search result JSON and return it when the agent queries logs for a given incident. Keeps demo snappy and eliminates Sumo auth/network risk.

---

## 4. Confluence API: Fetch Wiki Pages

### Endpoint & Auth

**Query by CQL** (use v1 API; v2 doesn't support CQL yet):
```
GET /wiki/rest/api/content/search?cql=space="DOCS" AND title~"Architecture"&expand=body.storage
```

**Query by space + page ID** (v2 API; modern):
```
GET /wiki/api/v2/spaces/{space_id}/pages/{page_id}?body-format=storage
```

**Auth:** API token (not password) issued per-user or for a bot account.

```bash
curl --header "Authorization: Bearer <token>" \
  "https://your_site.atlassian.net/wiki/rest/api/content/search?cql=space=DOCS"
```

### Minimal Call Sequence

1. **Search by project code** using CQL:
   ```
   GET /wiki/rest/api/content/search?cql=space="PROJ-X" AND type=page
   → Returns [ { "id": "page123", "title": "...", "space": {...} } ]
   ```
2. **Fetch full page body** (add expand parameter):
   ```
   GET /wiki/rest/api/content/search?cql=...&expand=body.storage
   ```
   Body is returned as HTML/XML; parse or convert to plain text.

3. **Alternative: fetch by page ID** (faster if you have the ID):
   ```
   GET /wiki/api/v2/spaces/{space_id}/pages/{page_id}?body-format=storage
   ```

### Authentication & Rate Limits

- **Token expiry:** API tokens can be revoked; plan for refresh.
- **Rate limit:** Confluence Cloud is generous (1000+ requests/min for most tenants).
- **Pagination:** Use `limit=50` (default) and `start` offset for large result sets.

### Demo Recommendation

**LIVE** (with cache fallback) — Confluence API is reliable. Cache wiki pages by project code at startup so you don't hammer the API during demo. Fallback: if fetch fails, return cached copy.

---

## 5. ServiceNow Post-Back: Add Work Note to Incident

### Endpoint & Method

**Add work note via Table API:**
```
PATCH /api/now/table/incident/{sys_id}
Content-Type: application/json
{
  "work_notes": "Auto-triage agent: category=DATABASE, severity=HIGH, owner_team=DBA"
}
```

**Auth:** Same as Table API read (Service Principal or Integration User).

### Minimal Call Sequence

1. **Extract incident sys_id** from the trigger payload.
2. **Prepare work note** (formatted, with agent signature).
3. **Send PATCH request:**
   ```
   PATCH /api/now/table/incident/{sys_id}
   Authorization: Basic <base64(user:pass)>
   Content-Type: application/json
   {
     "work_notes": "..."
   }
   ```
4. **Check response** (200 = success, 4xx = error).

### Important Notes

- **Field:** Use `work_notes` (internal, invisible to customer) not `comments` (customer-visible).
- **Retrieval:** Work notes appear in GET requests only with `sysparm_display_value=true` parameter.
- **Attribution:** Notes are credited to the integration user. To attribute to the actual analyst, authenticate as that user (or use impersonation if available).
- **Permissions:** The integration user needs `rest_service` role + permission to write to incident table.

### Demo Recommendation

**LIVE** — This is a single write. Very low risk. Reuse the same auth token from the trigger flow.

---

## Call Sequence: End-to-End Flow

```
1. Incident created in ServiceNow
   ↓
2. Flow Designer trigger fires → REST action
   ↓
3. External app receives webhook:
   {sys_id, project_code, description}
   ↓
4. Fetch context (in parallel):
   - GitLab: files from project
   - Sumo Logic: (mock) recent errors
   - Confluence: (cached) wiki pages
   ↓
5. LLM analyzes context → triage decision
   ↓
6. PATCH incident with work_notes
   ↓
7. Flow updates state to "Triaged" (optional)
```

---

## Integration Plumbing Risk Summary

| Component | Failure Mode | Mitigation |
|-----------|---|---|
| ServiceNow trigger (Flow Designer) | Plugin not installed; flow doesn't fire | Test flow execution before demo; use polling fallback if needed |
| GitLab auth (PAT) | Token expired or revoked | Pre-generate token; store in secure env var; test before demo |
| Sumo Logic (4 req/s limit) | Rate limit hit during demo if many incidents | Mock API; pre-seed results |
| Confluence token | Token expires mid-demo | Use service account; rotate before demo |
| ServiceNow post-back | Integration user lacks permissions | Pre-grant `rest_service` role and table write perms |
