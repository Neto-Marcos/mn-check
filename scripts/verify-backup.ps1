param(
  [Parameter(Mandatory = $true)][string]$BackupDirectory,
  [string]$PostgresBin = "",
  [string]$RestoreDatabaseUrl = ""
)

$ErrorActionPreference = "Stop"
$resolved = (Resolve-Path -LiteralPath $BackupDirectory).Path
$dump = Join-Path $resolved "mn-check.dump"
$manifest = Join-Path $resolved "manifest.txt"
if (-not (Test-Path -LiteralPath $dump) -or -not (Test-Path -LiteralPath $manifest)) { throw "Dump ou manifesto ausente." }

$expected = (Select-String -LiteralPath $manifest -Pattern '^SHA256=(.+)$').Matches.Groups[1].Value
$actual = (Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash
if ($expected -ne $actual) { throw "Checksum inválido. O dump foi alterado ou está corrompido." }

$pgRestorePath = if ($PostgresBin) { Join-Path $PostgresBin "pg_restore.exe" } else {
  $command = Get-Command pg_restore -ErrorAction SilentlyContinue
  if ($command) { $command.Source } else { "" }
}
if (-not $pgRestorePath -or -not (Test-Path -LiteralPath $pgRestorePath)) {
  throw "pg_restore não foi encontrado. Instale o cliente PostgreSQL ou informe -PostgresBin."
}
& $pgRestorePath --list $dump | Out-Null
if ($LASTEXITCODE -ne 0) { throw "O pg_restore não reconheceu o dump." }

if ($RestoreDatabaseUrl) {
  & $pgRestorePath --clean --if-exists --no-owner --no-privileges --exit-on-error `
      --dbname=$RestoreDatabaseUrl $dump
  if ($LASTEXITCODE -ne 0) { throw "A restauração no banco descartável falhou." }
  Write-Output "Restauração descartável concluída com sucesso."
}
Write-Output "Backup verificado com sucesso: $actual"
