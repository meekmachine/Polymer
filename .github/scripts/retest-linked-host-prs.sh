#!/usr/bin/env bash

# Fallback used by Polymer pkg.pr.new when LOOMLARGE_DISPATCH_TOKEN is set:
# directly rerun open LoomLarge PRs that Depends-on this Polymer PR.
# Primary path after LoomLarge merges Retest Linked Upstream PRs is schedule +
# repository_dispatch; this keeps retest working before that workflow lands on main.

set -euo pipefail

DEPENDENCY_REPO="${DEPENDENCY_REPO:?}"
DEPENDENCY_PR="${DEPENDENCY_PR:?}"
DEPENDENCY_SHA="${DEPENDENCY_SHA:-}"
HOST_REPO="${HOST_REPO:-meekmachine/LoomLarge}"
WORKFLOWS="${WORKFLOWS:-PR Tests,Deploy PR Preview}"

dependency_basename="${DEPENDENCY_REPO##*/}"
dependency_slug="$(printf '%s' "${dependency_basename}" | tr '[:upper:]' '[:lower:]')"

echo "Looking for open PRs in ${HOST_REPO} that link ${DEPENDENCY_REPO}#${DEPENDENCY_PR}"
if [[ -n "${DEPENDENCY_SHA}" ]]; then
  echo "Upstream head SHA: ${DEPENDENCY_SHA}"
fi

prs_json="$(
  gh pr list \
    --repo "${HOST_REPO}" \
    --state open \
    --limit 100 \
    --json number,title,body,headRefName
)"

matched_json="$(
  DEPENDENCY_PR="${DEPENDENCY_PR}" \
  DEPENDENCY_REPO="${DEPENDENCY_REPO}" \
  DEPENDENCY_BASENAME="${dependency_basename}" \
  DEPENDENCY_SLUG="${dependency_slug}" \
  PRS_JSON="${prs_json}" \
  python3 <<'PY'
import json
import os
import re

prs = json.loads(os.environ["PRS_JSON"])
pr = os.environ["DEPENDENCY_PR"]
repo = os.environ["DEPENDENCY_REPO"]
basen = os.environ["DEPENDENCY_BASENAME"]
slug = os.environ["DEPENDENCY_SLUG"]
patterns = [
    rf"https://github\.com/{re.escape(repo)}/pull/{pr}\b",
    rf"{re.escape(repo)}#{pr}\b",
    rf"{re.escape(basen)}#{pr}\b",
    rf"(?i)depends-on:\s*{re.escape(slug)}#{pr}\b",
    rf"(?i)depends-on:\s*{re.escape(basen)}#{pr}\b",
    rf"(?i)depends-on:\s*{re.escape(repo)}#{pr}\b",
]

matched = []
for item in prs:
    body = item.get("body") or ""
    if any(re.search(p, body, flags=re.I) for p in patterns):
        matched.append(
            {
                "number": item["number"],
                "headRefName": item["headRefName"],
                "title": item["title"],
            }
        )

print(json.dumps(matched))
PY
)"

if [[ "${matched_json}" == "[]" ]]; then
  echo "No open host PRs link ${DEPENDENCY_REPO}#${DEPENDENCY_PR}."
  exit 0
fi

reran=0
while IFS= read -r row; do
  number="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["number"])' "${row}")"
  branch="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["headRefName"])' "${row}")"
  title="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["title"])' "${row}")"

  echo "Matched host PR #${number} (${branch}): ${title}"

  IFS=',' read -r -a workflow_names <<< "${WORKFLOWS}"
  for workflow_name in "${workflow_names[@]}"; do
    workflow_name="$(echo "${workflow_name}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -z "${workflow_name}" ]] && continue

    active="$(
      gh run list \
        --repo "${HOST_REPO}" \
        --branch "${branch}" \
        --workflow "${workflow_name}" \
        --limit 1 \
        --json databaseId,status \
        --jq '.[0] | select(.status == "queued" or .status == "in_progress" or .status == "waiting" or .status == "pending") | .databaseId // empty'
    )"
    if [[ -n "${active}" ]]; then
      echo "  '${workflow_name}' already running (${active}); skipping."
      continue
    fi

    run_id="$(
      gh run list \
        --repo "${HOST_REPO}" \
        --branch "${branch}" \
        --workflow "${workflow_name}" \
        --limit 5 \
        --json databaseId,status \
        --jq '[.[] | select(.status == "completed")][0].databaseId // empty'
    )"

    if [[ -z "${run_id}" ]]; then
      echo "  No prior completed '${workflow_name}' run on ${branch}; skipping."
      continue
    fi

    echo "  Rerunning '${workflow_name}' run ${run_id}"
    gh run rerun "${run_id}" --repo "${HOST_REPO}"
    reran=$((reran + 1))
  done
done < <(python3 -c 'import json,sys; [print(json.dumps(x)) for x in json.loads(sys.argv[1])]' "${matched_json}")

echo "Reran ${reran} host workflow run(s)."
