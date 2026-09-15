#!/usr/bin/env bash
# One default, credential-free verification entry point for contributors and CI.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew -PGMC_VERIFY=true -I scripts/dependency-inventory.init.gradle \
  :app:publicationDependencyInventory :app:verifyCoverage \
  :app:lintDebug :app:assembleDebug :app:assembleRelease \
  --console=plain --build-cache --profile
python3 scripts/generate-dependency-notices.py --check
python3 -m unittest discover -s scripts/tests
(cd mockserver && go test -race ./... && go vet ./...)
