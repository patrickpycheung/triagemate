# GitLab — getting credentials

Used by: real GitLab gateway (`triage.connectors.gitlab=real`).
Fills `secrets.properties` keys `triage.integrations.gitlab.{base-url,token}`.

Auth is a **Personal Access Token** (or **Project Access Token**) sent as a bearer
token against the GitLab REST API.

## 1. Create a token

Prefer a **Project Access Token** scoped to just the repo(s) the app needs to read,
over a Personal Access Token tied to your account:

### Project Access Token (recommended)

1. In the target GitLab project, go to **Settings** → **Access Tokens**.
2. Give it a name (e.g. `triagemate-demo`), set an expiry date.
3. Role: **Reporter** (read-only access to code/repository is enough).
4. Scope: **`read_api`** (and `read_repository` if you also need raw file/blob reads
   beyond the API).
5. **Create project access token** → copy it immediately, it's shown once.

### Personal Access Token (if you don't have project-admin rights)

1. GitLab avatar → **Edit profile** → **Access Tokens**.
2. Name it, set an expiry, scope: **`read_api`**.
3. **Create personal access token** → copy it immediately.

## 2. Find your base URL

- **gitlab.com**: `https://gitlab.com`
- **Self-hosted**: your instance's URL, e.g. `https://gitlab.your-company.com`

## 3. Confirm access

```bash
curl --header "PRIVATE-TOKEN: <token>" \
  "https://gitlab.com/api/v4/projects?membership=true&per_page=1"
```

A `200` with a JSON project list confirms it's working.

## 4. Fill `secrets.properties`

```properties
triage.integrations.gitlab.base-url=https://gitlab.com
triage.integrations.gitlab.token=<token from step 1>
```

## Notes

- The app only reads source files/blobs to correlate log lines to code — it never
  pushes, comments, or opens MRs. `read_api` (+ `read_repository` if needed) is
  sufficient; never use a token with `write_repository` or `api` (full write) scope.
- Set an expiry on the token and rotate it — GitLab tokens don't auto-notify on
  expiry, so pick a date past your demo.
