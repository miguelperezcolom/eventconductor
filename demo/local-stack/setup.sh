#!/usr/bin/env bash
# Materialize the git-import source repos the engine reads via file://.
#
# wf-repo/ and forms-repo/ are versioned here as plain files, but the orchestrator and forms import
# their definitions with git (WORKFLOW_GITIMPORT / FORMS_GITIMPORT, url = file:///wf-repo etc.), so
# each must be a git repo at runtime. This inits a local git repo in each (their .git is gitignored,
# i.e. not part of the eventconductor repo). Run once before the first `docker compose up`; re-running
# is a no-op.
set -euo pipefail
cd "$(dirname "$0")"

for d in wf-repo forms-repo; do
  if [ -d "$d/.git" ]; then
    echo "$d: already a git repo"
  else
    ( cd "$d" \
        && git init -q \
        && git add -A \
        && git -c user.email=demo@local -c user.name=demo commit -qm "local git-import source" )
    echo "$d: initialized as a git-import repo"
  fi
done

echo "Done. Now: docker compose up -d"
