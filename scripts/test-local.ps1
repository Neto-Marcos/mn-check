$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localMaven = Join-Path $env:USERPROFILE "Tools\apache-maven-3.9.11\bin\mvn.cmd"

if (Get-Command mvn -ErrorAction SilentlyContinue) {
  $maven = "mvn"
} elseif (Test-Path -LiteralPath $localMaven) {
  $maven = $localMaven
} else {
  throw "Maven nao encontrado. Instale o Maven ou confira C:\Users\<usuario>\Tools\apache-maven-3.9.11\bin\mvn.cmd."
}

Push-Location $repoRoot
try {
  & $maven clean test
} finally {
  Pop-Location
}
