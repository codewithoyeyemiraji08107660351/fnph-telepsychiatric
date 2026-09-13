#!/usr/bin/env bash
#
# The test runner used by docs/TESTING.md.
#
# Stages are ordered so the cheapest, fastest failures surface first. There is
# no point running a five-minute container suite to discover the code does not
# compile.
#
# Usage:
#   scripts/test.sh compile     javac only, no tests
#   scripts/test.sh unit        fast tests, no database
#   scripts/test.sh schema      migrations and constraints, needs Docker
#   scripts/test.sh gates       the acceptance gates, needs Docker
#   scripts/test.sh all         everything, in order (default)

set -euo pipefail

STAGE="${1:-all}"
MVN="./mvnw"
[ -x "$MVN" ] || MVN="mvn"

# Every fast test, by class. Named explicitly rather than by pattern so a new
# test that should be in this list has to be added deliberately.
UNIT_TESTS="PublicIdTest,TokensTest,TotpServiceTest,PasswordPolicyTest,\
AuditChainTest,SupervisionRulesTest,SessionClockTest,PhoneNormalisationTest,\
PaymentRulesTest,BookingSequenceTest,TenantScopeTest"

SCHEMA_TESTS="MigrationReplayTest,SchemaConventionsTest,PermissionMatrixTest,\
AuthSchemaTest,GovernanceSchemaTest,EhrVerificationSchemaTest,PaymentSchemaTest,\
SchedulingSchemaTest,ConsultationSchemaTest,ClinicalOutputSchemaTest,\
DocumentSchemaTest,CentrePathwaySchemaTest,HelpdeskSchemaTest,TenantIsolationTest"

GATE_TESTS="SlotConcurrencyAcceptanceTest,DuplicateWebhookAcceptanceTest,\
TenantIsolationTest,AuditChainTest"

banner() { printf '\n=== %s ===\n' "$1"; }

require_docker() {
  if ! docker info >/dev/null 2>&1; then
    echo "Docker is not running. The schema and gate stages need it: they run"
    echo "against a real MySQL 8.4 container, not H2."
    exit 1
  fi
}

compile() {
  banner "Compile"
  $MVN -q clean compile
  $MVN -q test-compile
  echo "Compiled."
}

unit() {
  banner "Unit tests (no database)"
  $MVN test -Dtest="$UNIT_TESTS" -DfailIfNoTests=false
}

schema() {
  require_docker
  banner "Schema and migration tests (MySQL container)"
  $MVN test -Dtest="$SCHEMA_TESTS" -DfailIfNoTests=false
}

gates() {
  require_docker
  banner "Acceptance gates"
  $MVN test -Dtest="$GATE_TESTS" -DfailIfNoTests=false
  echo
  echo "Gates covered here: slot concurrency, duplicate provider callbacks,"
  echo "cross-tenant isolation, audit reconstruction."
  echo "Restore-from-backup is a separate drill: scripts/restore.sh"
}

case "$STAGE" in
  compile) compile ;;
  unit)    unit ;;
  schema)  schema ;;
  gates)   gates ;;
  all)     compile; unit; schema; gates ;;
  *)       echo "Unknown stage: $STAGE"; exit 1 ;;
esac

banner "Done"
