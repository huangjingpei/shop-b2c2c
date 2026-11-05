# File: test_backup_windows.ps1
# Purpose: Verify backup_mysql.ps1 in Windows PowerShell 5.1 / 7+ environment (R31/T23):
#          password injection, failure cleanup, no-BOM SQL, stderr separation, defaults-extra-file args, no docker fallback.
# Maintenance: runs only on Windows; isolated temp dirs and mock mysqldump, never touches a real database.
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackupScript = Join-Path (Split-Path -Parent $ScriptDir) "scripts\backup_mysql.ps1"

$PassCount = 0
$FailCount = 0

function Assert-Test {
    param(
        [string]$TestName,
        [bool]$Condition,
        [string]$Details = ""
    )
    if ($Condition) {
        $script:PassCount++
        Write-Host "  [PASS] $TestName"
    } else {
        $script:FailCount++
        Write-Host "  [FAIL] $TestName : $Details"
    }
}

function Get-GzipFirstByte {
    param([string]$Path)
    $fs = [System.IO.File]::OpenRead($Path)
    try {
        $gz = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
        $buf = New-Object byte[] 1
        $null = $gz.Read($buf, 0, 1)
        $gz.Dispose()
        return $buf[0]
    } finally {
        $fs.Dispose()
    }
}

function Get-GzipText {
    param([string]$Path)
    $fs = [System.IO.File]::OpenRead($Path)
    try {
        $gz = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
        $sr = New-Object System.IO.StreamReader($gz, [System.Text.Encoding]::UTF8)
        $text = $sr.ReadToEnd()
        $sr.Dispose()
        return $text
    } finally {
        $fs.Dispose()
    }
}

Write-Host "=== Start Windows PowerShell Backup Validation (R31/T23) ==="

# -----------------------------------------------------------------------------
# TC-R31-01: Abort when DB_PASS is missing
# -----------------------------------------------------------------------------
$t1Dir = Join-Path $env:TEMP "ps1_test_tc1_$(Get-Random)"
New-Item -ItemType Directory -Path $t1Dir | Out-Null
$oldPass = $env:DB_PASS
$env:DB_PASS = ""

try {
    $proc = Start-Process -FilePath "powershell.exe" -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$BackupScript`" -DbName `"test_db`" -BackupDir `"$t1Dir`"" -NoNewWindow -Wait -PassThru
    Assert-Test "TC-R31-01: Abort when DB_PASS missing" ($proc.ExitCode -ne 0) "ExitCode was $($proc.ExitCode)"
    $files = Get-ChildItem -Path $t1Dir -Filter "*.sql.gz"
    Assert-Test "TC-R31-01: No gz files created on missing pass" ($files.Count -eq 0) "Found $($files.Count) files"
} finally {
    $env:DB_PASS = $oldPass
    Remove-Item -Path $t1Dir -Recurse -Force -ErrorAction SilentlyContinue
}

# -----------------------------------------------------------------------------
# TC-R31-02: Abort and clean up temporary files when mysqldump fails
# -----------------------------------------------------------------------------
$t2Dir = Join-Path $env:TEMP "ps1_test_tc2_$(Get-Random)"
$t2Bin = Join-Path $t2Dir "bin"
New-Item -ItemType Directory -Path $t2Bin | Out-Null
$mockFailDump = Join-Path $t2Bin "mysqldump.cmd"
[System.IO.File]::WriteAllText($mockFailDump, "@echo off`r`necho Error: Access denied 1>&2`r`nexit /b 2`r`n", [System.Text.Encoding]::ASCII)

$oldPath = $env:PATH
$env:PATH = "$t2Bin;$oldPath"

try {
    $proc = Start-Process -FilePath "powershell.exe" -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$BackupScript`" -DbName `"test_db`" -DbPass `"Secret123!`" -BackupDir `"$t2Dir`"" -NoNewWindow -Wait -PassThru
    Assert-Test "TC-R31-02: Non-zero exit code when mysqldump fails" ($proc.ExitCode -ne 0) "ExitCode was $($proc.ExitCode)"
    $gzFiles = Get-ChildItem -Path $t2Dir -Filter "*.sql.gz"
    $sqlFiles = Get-ChildItem -Path $t2Dir -Filter "*.sql"
    Assert-Test "TC-R31-02: Intermediate sql cleaned up on failure" ($sqlFiles.Count -eq 0) "Found $($sqlFiles.Count) sql files"
    Assert-Test "TC-R31-02: No broken gz file left on failure" ($gzFiles.Count -eq 0) "Found $($gzFiles.Count) gz files"
} finally {
    $env:PATH = $oldPath
    Remove-Item -Path $t2Dir -Recurse -Force -ErrorAction SilentlyContinue
}

# -----------------------------------------------------------------------------
# TC-R31-03: Create valid non-empty .sql.gz and clean up intermediate .sql
# -----------------------------------------------------------------------------
$t3Dir = Join-Path $env:TEMP "ps1_test_tc3_$(Get-Random)"
$t3Bin = Join-Path $t3Dir "bin"
New-Item -ItemType Directory -Path $t3Bin | Out-Null
$mockOkDump = Join-Path $t3Bin "mysqldump.cmd"
[System.IO.File]::WriteAllText($mockOkDump, "@echo off`r`necho -- Mock MySQL Dump`r`necho Warning: forced stderr text 1>&2`r`necho CREATE TABLE t(id int);`r`n", [System.Text.Encoding]::ASCII)

$oldPath = $env:PATH
$env:PATH = "$t3Bin;$oldPath"

try {
    $proc = Start-Process -FilePath "powershell.exe" -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$BackupScript`" -DbName `"shop_test`" -DbPass `"Secret123!`" -BackupDir `"$t3Dir`"" -NoNewWindow -Wait -PassThru
    Assert-Test "TC-R31-03: Exit code 0 on successful backup" ($proc.ExitCode -eq 0) "ExitCode was $($proc.ExitCode)"
    $gzFiles = Get-ChildItem -Path $t3Dir -Filter "shop_shop_test_*.sql.gz"
    Assert-Test "TC-R31-03: Output gz file matches naming schema" ($gzFiles.Count -eq 1) "Found $($gzFiles.Count) files"
    if ($gzFiles.Count -eq 1) {
        Assert-Test "TC-R31-03: Output gz file size greater than 0" ($gzFiles[0].Length -gt 0) "File size was $($gzFiles[0].Length)"
        $firstByte = Get-GzipFirstByte $gzFiles[0].FullName
        Assert-Test "TC-R31-03: SQL has no UTF-8 BOM" ($firstByte -ne 0xEF) "First byte was $firstByte"
        $sqlText = Get-GzipText $gzFiles[0].FullName
        Assert-Test "TC-R31-03: stderr warning not mixed into SQL" (-not ($sqlText -match 'forced stderr')) "SQL contained stderr text"
        Assert-Test "TC-R31-03: SQL content preserved" ($sqlText -match 'CREATE TABLE t') "SQL content missing"
    }
    $sqlFiles = Get-ChildItem -Path $t3Dir -Filter "*.sql"
    Assert-Test "TC-R31-03: Intermediate sql file cleaned up" ($sqlFiles.Count -eq 0) "Found $($sqlFiles.Count) sql files"
} finally {
    $env:PATH = $oldPath
    Remove-Item -Path $t3Dir -Recurse -Force -ErrorAction SilentlyContinue
}

# -----------------------------------------------------------------------------
# TC-R31-04: Retention cleanup (deletes expired archives, keeps fresh ones)
# -----------------------------------------------------------------------------
$t4Dir = Join-Path $env:TEMP "ps1_test_tc4_$(Get-Random)"
$t4Bin = Join-Path $t4Dir "bin"
New-Item -ItemType Directory -Path $t4Bin | Out-Null
$mockRotDump = Join-Path $t4Bin "mysqldump.cmd"
[System.IO.File]::WriteAllText($mockRotDump, "@echo off`r`necho CREATE TABLE t_rot(id int);`r`n", [System.Text.Encoding]::ASCII)

$oldPath = $env:PATH
$env:PATH = "$t4Bin;$oldPath"

try {
    # Create 10-day-old file
    $oldFile = Join-Path $t4Dir "shop_test_rot_20260101_000000.sql.gz"
    [System.IO.File]::WriteAllText($oldFile, "fake-old-archive")
    (Get-Item $oldFile).LastWriteTime = (Get-Date).AddDays(-10)

    # Create 2-day-old file
    $newFile = Join-Path $t4Dir "shop_test_rot_20260109_000000.sql.gz"
    [System.IO.File]::WriteAllText($newFile, "fake-recent-archive")
    (Get-Item $newFile).LastWriteTime = (Get-Date).AddDays(-2)

    $proc = Start-Process -FilePath "powershell.exe" -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$BackupScript`" -DbName `"test_rot`" -DbPass `"Secret123!`" -BackupDir `"$t4Dir`" -RetentionDays 7" -NoNewWindow -Wait -PassThru
    Assert-Test "TC-R31-04: Exit code 0 on rotation" ($proc.ExitCode -eq 0) "ExitCode was $($proc.ExitCode)"
    Assert-Test "TC-R31-04: Expired file deleted" (-not (Test-Path $oldFile)) "Old file still exists"
    Assert-Test "TC-R31-04: Fresh file kept" (Test-Path $newFile) "Recent file was incorrectly removed"
    $allGz = Get-ChildItem -Path $t4Dir -Filter "shop_test_rot_*.sql.gz"
    Assert-Test "TC-R31-04: Kept fresh file and created new archive" ($allGz.Count -eq 2) "Found $($allGz.Count) gz files"
} finally {
    $env:PATH = $oldPath
    Remove-Item -Path $t4Dir -Recurse -Force -ErrorAction SilentlyContinue
}

# -----------------------------------------------------------------------------
# TC-T23-05: Credentials via --defaults-extra-file, not visible in argv, temp file removed
# -----------------------------------------------------------------------------
$t5Dir = Join-Path $env:TEMP "ps1_test_tc5_$(Get-Random)"
$t5Bin = Join-Path $t5Dir "bin"
New-Item -ItemType Directory -Path $t5Bin | Out-Null
$t5ArgsLog = Join-Path $t5Bin "args.log"
$mockArgsDump = Join-Path $t5Bin "mysqldump.cmd"
[System.IO.File]::WriteAllText($mockArgsDump, "@echo off`r`necho %* > `"%~dp0args.log`"`r`necho CREATE TABLE t_args(id int);`r`n", [System.Text.Encoding]::ASCII)

$oldPath = $env:PATH
$env:PATH = "$t5Bin;$oldPath"

try {
    $proc = Start-Process -FilePath "powershell.exe" -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$BackupScript`" -DbName `"test_args`" -DbPass `"Secret123!`" -BackupDir `"$t5Dir`"" -NoNewWindow -Wait -PassThru
    Assert-Test "TC-T23-05: Backup succeeds with defaults file" ($proc.ExitCode -eq 0) "ExitCode was $($proc.ExitCode)"
    $argText = if (Test-Path $t5ArgsLog) { Get-Content $t5ArgsLog -Raw } else { "" }
    Assert-Test "TC-T23-05: Uses --defaults-extra-file" ($argText -match '--defaults-extra-file=') "argv: $argText"
    Assert-Test "TC-T23-05: Uses --no-tablespaces" ($argText -match '--no-tablespaces') "argv: $argText"
    Assert-Test "TC-T23-05: Password not present in argv" (-not ($argText -match 'Secret123!')) "password leaked into argv"
    $match = [regex]::Match($argText, '--defaults-extra-file="([^"]+)"')
    if ($match.Success) {
        Assert-Test "TC-T23-05: Credentials temp file removed" (-not (Test-Path $match.Groups[1].Value)) "temp file still exists: $($match.Groups[1].Value)"
    } else {
        Assert-Test "TC-T23-05: Credentials path parsed from argv" $false "could not parse defaults-extra-file path"
    }
} finally {
    $env:PATH = $oldPath
    Remove-Item -Path $t5Dir -Recurse -Force -ErrorAction SilentlyContinue
}

# -----------------------------------------------------------------------------
# TC-T23-06: No docker fallback in the script
# -----------------------------------------------------------------------------
$scriptText = Get-Content $BackupScript -Raw
Assert-Test "TC-T23-06: No docker ps/exec fallback" (-not ($scriptText -match 'docker\s+(ps|exec)')) "script still references docker"
Assert-Test "TC-T23-06: Script uses defaults-extra-file" ($scriptText -match '--defaults-extra-file') "missing defaults-extra-file"
Assert-Test "TC-T23-06: Script uses no-tablespaces" ($scriptText -match '--no-tablespaces') "missing no-tablespaces"

Write-Host "`n=== Test Results Summary ==="
Write-Host "Passed: $PassCount, Failed: $FailCount"
if ($FailCount -gt 0) {
    exit 1
} else {
    Write-Host "All Windows PowerShell backup test cases passed successfully!"
    exit 0
}
