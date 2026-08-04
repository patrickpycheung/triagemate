#!/usr/bin/env bash
# TriageMate — ADK live agent (D1) against REAL connectors. The full thing:
# a real model reasoning over real data from all four live systems.
#
# The highest-risk configuration on stage — it depends on the Copilot proxy AND
# every connector's network path. ./run-adk.sh (real model, mock data) is the safer
# demo: same live agent behaviour, no connector network.
#
# Needs the Copilot proxy (run-adk.sh starts one) plus credentials for all four
# systems in secrets.properties.
#
# For only SOME connectors real, don't use this script — pass the individual keys
# to the mock one instead, e.g.:
#   ./run-adk.sh --triage.connectors.servicenow=real
#
# Usage: ./run-adk-real.sh [extra --app.args=...]
exec "$(dirname "$0")/run-adk.sh" --spring.profiles.active=real "$@"
