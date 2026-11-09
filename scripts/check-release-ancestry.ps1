param(
    [Parameter(Mandatory = $true)][string]$DeployedCommit,
    [string]$CandidateCommit = 'HEAD'
)
$ErrorActionPreference = 'Stop'
# Read-only: run after fetching/importing the actual deployed commit.
foreach ($revision in @($DeployedCommit, $CandidateCommit)) {
    & git cat-file -e "$revision^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Unknown commit: $revision. Fetch/import it before release." }
}
& git merge-base --is-ancestor $DeployedCommit $CandidateCommit
if ($LASTEXITCODE -ne 0) {
    throw 'Release blocked: candidate does not include the currently deployed commit. Merge and verify before deploying.'
}
Write-Output 'PASS: candidate includes the deployed commit. Tests, backups and deployment approval remain required.'
