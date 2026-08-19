param(
  [Parameter(Mandatory = $true)][string]$DatabaseUrl,
  [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\outputs\backups"),
  [string]$PostgresBin = ""
)

$ErrorActionPreference = "Stop"
$pgDumpPath = if ($PostgresBin) { Join-Path $PostgresBin "pg_dump.exe" } else {
  $command = Get-Command pg_dump -ErrorAction SilentlyContinue
  if ($command) { $command.Source } else { "" }
}
if (-not $pgDumpPath -or -not (Test-Path -LiteralPath $pgDumpPath)) {
  throw "pg_dump não foi encontrado. Instale o cliente PostgreSQL ou informe -PostgresBin."
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupDirectory = Join-Path $OutputDirectory "mn-check-$timestamp"
New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
$dumpPath = Join-Path $backupDirectory "mn-check.dump"

& $pgDumpPath --format=custom --no-owner --no-privileges --file=$dumpPath $DatabaseUrl
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $dumpPath)) { throw "O pg_dump não concluiu o backup." }

$hash = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash
$manifest = @"
MN Check PostgreSQL backup
CreatedAt=$((Get-Date).ToUniversalTime().ToString("o"))
Format=PostgreSQL custom
File=mn-check.dump
SHA256=$hash
"@
$manifestPath = Join-Path $backupDirectory "manifest.txt"
$patchText = $manifest
[System.IO.File]::WriteAllText($manifestPath, $patchText, [System.Text.UTF8Encoding]::new($false))

Write-Output "Backup criado: $backupDirectory"
Write-Output "SHA256: $hash"
