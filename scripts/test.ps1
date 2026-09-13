# Windows equivalent of scripts/test.sh. Same stages, same order.
#
#   .\scripts\test.ps1 compile
#   .\scripts\test.ps1 all

param([string]$Stage = "all")

$ErrorActionPreference = "Stop"
$mvn = if (Test-Path ".\mvnw.cmd") { ".\mvnw.cmd" } else { "mvn" }

$unit = "PublicIdTest,TokensTest,TotpServiceTest,PasswordPolicyTest,AuditChainTest," +
        "SupervisionRulesTest,SessionClockTest,PhoneNormalisationTest,PaymentRulesTest," +
        "BookingSequenceTest,TenantScopeTest"

$schema = "MigrationReplayTest,SchemaConventionsTest,PermissionMatrixTest,AuthSchemaTest," +
          "GovernanceSchemaTest,EhrVerificationSchemaTest,PaymentSchemaTest," +
          "SchedulingSchemaTest,ConsultationSchemaTest,ClinicalOutputSchemaTest," +
          "DocumentSchemaTest,CentrePathwaySchemaTest,HelpdeskSchemaTest,TenantIsolationTest"

$gates = "SlotConcurrencyAcceptanceTest,DuplicateWebhookAcceptanceTest," +
         "TenantIsolationTest,AuditChainTest"

function Banner($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }

function Require-Docker {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Docker is not running. The schema and gate stages need a real MySQL container."
        exit 1
    }
}

function Invoke-Compile { Banner "Compile"; & $mvn -q clean compile; & $mvn -q test-compile }
function Invoke-Unit    { Banner "Unit tests"; & $mvn test "-Dtest=$unit" -DfailIfNoTests=false }
function Invoke-Schema  { Require-Docker; Banner "Schema tests"; & $mvn test "-Dtest=$schema" -DfailIfNoTests=false }
function Invoke-Gates   { Require-Docker; Banner "Acceptance gates"; & $mvn test "-Dtest=$gates" -DfailIfNoTests=false }

switch ($Stage) {
    "compile" { Invoke-Compile }
    "unit"    { Invoke-Unit }
    "schema"  { Invoke-Schema }
    "gates"   { Invoke-Gates }
    "all"     { Invoke-Compile; Invoke-Unit; Invoke-Schema; Invoke-Gates }
    default   { Write-Host "Unknown stage: $Stage"; exit 1 }
}
Banner "Done"
