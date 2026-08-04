#!/usr/bin/env bash
# TriageMate — deterministic engine (D2) against REAL connectors.
#
# Scripted reasoning (no LLM), but every gateway hits the live system: real
# ServiceNow incident, real Confluence/Sumo/GitLab evidence. Use this to prove the
# connectors genuinely work, without the variability of a live model on top.
#
# Needs credentials for all four systems in secrets.properties (copy
# secrets.properties.example). Missing ones surface as errors from that gateway.
#
# For only SOME connectors real, don't use this script — pass the individual keys
# to the mock one instead, e.g.:
#   ./run-deterministic.sh --triage.connectors.servicenow=real
#
# Usage: ./run-deterministic-real.sh [extra --app.args=...]
exec "$(dirname "$0")/run-deterministic.sh" --spring.profiles.active=real "$@"
