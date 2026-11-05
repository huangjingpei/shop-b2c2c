# 文件：backup_mysql.ps1
# 用途：MySQL导出、压缩归档及历史备份清理工具。
# 维护注意：会读取真实库并按保留周期删除旧备份；确认目标库、导出工具与输出目录。
#          DB_PASS 必须通过环境变量 $env:DB_PASS 或 -DbPass 参数显式注入，不允许硬编码默认密码。
#          口令经临时 --defaults-extra-file 传递，不出现在 mysqldump 参数；仅使用系统 mysqldump（不回退 Docker），
#          stdout/stderr 分离，SQL 以原始字节落盘（无 BOM），gzip 流式压缩，失败清理临时文件。
#          备份成功后必须另做隔离恢复演练（restore drill）。
<#
.SYNOPSIS
    shop-b2b2c Windows / PowerShell 环境 MySQL 数据库定时冷备与归档脚本
.DESCRIPTION
    支持全量事务级一致性导出、Gzip 压缩归档、完整性检查、30天自动清理及失败告警
#>

param (
    [string]$DbHost = "",
    [int]$DbPort = 0,
    [string]$DbName = "",
    [string]$DbUser = "",
    [string]$DbPass = "",
    [string]$BackupDir = "",
    [int]$RetentionDays = 30,
    [string]$AlarmWebhook = ""
)

$ErrorActionPreference = "Stop"

# 参数与环境变量回退解析（兼容 PowerShell 5.1+ 参数块语法规范）
if (-not $DbHost) { $DbHost = if ($env:DB_HOST) { $env:DB_HOST } else { "127.0.0.1" } }
if (-not $DbPort) { $DbPort = if ($env:DB_PORT) { [int]$env:DB_PORT } else { 3306 } }
if (-not $DbName) { $DbName = if ($env:DB_NAME) { $env:DB_NAME } else { "shop_b2b2c" } }
if (-not $DbUser) { $DbUser = if ($env:DB_USER) { $env:DB_USER } else { "shop_b2b2c" } }
if (-not $DbPass) { $DbPass = $env:DB_PASS }
if (-not $BackupDir) { $BackupDir = if ($env:BACKUP_DIR) { $env:BACKUP_DIR } else { "E:\shop-b2b2c\backups\mysql" } }
if (-not $AlarmWebhook) { $AlarmWebhook = $env:ALARM_WEBHOOK_URL }

# ---------- 密码前置检查：拒绝使用空密码备份生产库 ----------
if ([string]::IsNullOrWhiteSpace($DbPass)) {
    Write-Error "DB_PASS 未设置，拒绝使用默认凭据备份生产库，请通过环境变量 DB_PASS 或 -DbPass 参数注入密码后重试。"
    exit 1
}

$DateStr = Get-Date -Format "yyyyMMdd_HHmmss"
$BackupSqlFile = Join-Path $BackupDir "shop_${DbName}_${DateStr}.sql"
$BackupGzFile = "${BackupSqlFile}.gz"
$DumpErrorFile = Join-Path $BackupDir "shop_${DbName}_${DateStr}.dump.err"
$LogFile = Join-Path $BackupDir "backup.log"
$MySqlDefaultsFile = ""

if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

function Write-Log {
    param([string]$Message)
    $Timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $Line = "[$Timestamp] [BACKUP] $Message"
    Write-Host $Line
    Add-Content -Path $LogFile -Value $Line
}

function Send-Alarm {
    param([string]$ErrorMessage)
    if ($AlarmWebhook) {
        $Body = @{
            msgtype = "markdown"
            markdown = @{
                content = "### 数据库备份失败告警 (Windows)`n> 错误: $ErrorMessage`n> 时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
            }
        } | ConvertTo-Json -Compress
        try {
            Invoke-RestMethod -Uri $AlarmWebhook -Method Post -ContentType "application/json; charset=utf-8" -Body $Body | Out-Null
        } catch {
            Write-Warning "告警发送失败: $_"
        }
    }
}

Write-Log "====== 开始执行数据库全量一致性备份 ======"
Write-Log "目标库: ${DbName}@${DbHost}:${DbPort}, 输出: $BackupGzFile"

try {
    # 仅支持本机 mysqldump；缺失即失败，避免 Docker 环境差异导致备份静默为空。
    $DumpCommand = Get-Command mysqldump -ErrorAction SilentlyContinue
    if (-not $DumpCommand) {
        throw "未找到 mysqldump 可执行文件，备份终止（不再回退 Docker）"
    }
    $MysqldumpPath = $DumpCommand.Source

    # 凭据写临时文件，mysqldump 通过 --defaults-extra-file 读取，口令不进入进程参数。
    $MySqlDefaultsFile = Join-Path ([System.IO.Path]::GetTempPath()) ("mysql-backup-" + [guid]::NewGuid().ToString("N") + ".cnf")
    $DefaultsContent = "[client]`nhost=$DbHost`nport=$DbPort`nuser=$DbUser`npassword=$DbPass`n"
    [System.IO.File]::WriteAllText($MySqlDefaultsFile, $DefaultsContent, (New-Object System.Text.UTF8Encoding($false)))
    try {
        & icacls $MySqlDefaultsFile /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null
    } catch {
        Write-Warning "无法收紧凭据临时文件 ACL，将在 finally 中删除: $_"
    }

    $dumpArgs = @(
        "--defaults-extra-file=`"$MySqlDefaultsFile`"",
        "--single-transaction",
        "--quick",
        "--routines",
        "--triggers",
        "--no-tablespaces",
        "--default-character-set=utf8mb4",
        $DbName
    ) -join " "

    # .cmd/.bat 包装器必须经 cmd.exe 启动；真实 mysqldump.exe 直接执行。
    if ($MysqldumpPath -match '\.(cmd|bat)$') {
        $FilePath = $env:ComSpec
        $Arguments = "/c `"`"$MysqldumpPath`" $dumpArgs`""
    } else {
        $FilePath = $MysqldumpPath
        $Arguments = $dumpArgs
    }
    $proc = Start-Process -FilePath $FilePath -ArgumentList $Arguments `
        -RedirectStandardOutput $BackupSqlFile -RedirectStandardError $DumpErrorFile `
        -NoNewWindow -Wait -PassThru
    if ($proc.ExitCode -ne 0) {
        $errText = if (Test-Path $DumpErrorFile) { (Get-Content $DumpErrorFile -Raw -ErrorAction SilentlyContinue) } else { "" }
        throw "mysqldump 返回非零退出码 $($proc.ExitCode)：$errText"
    }

    if (-not (Test-Path $BackupSqlFile) -or (Get-Item $BackupSqlFile).Length -eq 0) {
        throw "导出的 SQL 文件为空或不存在，备份异常！"
    }

    # 流式 Gzip 压缩：不把整个 SQL 读入内存，也不经过 PowerShell 文本编码（避免 BOM）。
    Write-Log "执行 Gzip 流式压缩..."
    $inputStream = [System.IO.File]::Open($BackupSqlFile, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    $outputStream = [System.IO.File]::Create($BackupGzFile)
    try {
        $gzipStream = New-Object System.IO.Compression.GZipStream($outputStream, [System.IO.Compression.CompressionMode]::Compress)
        try {
            $buffer = New-Object byte[] 65536
            while (($read = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $gzipStream.Write($buffer, 0, $read)
            }
        } finally {
            $gzipStream.Dispose()
        }
    } finally {
        $outputStream.Dispose()
        $inputStream.Dispose()
    }

    Remove-Item -Path $BackupSqlFile -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $DumpErrorFile -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path $BackupGzFile) -or (Get-Item $BackupGzFile).Length -eq 0) {
        throw "压缩后的归档文件为空或不存在，备份异常！"
    }

    $FileSize = (Get-Item $BackupGzFile).Length / 1KB
    Write-Log "[OK] 备份成功！大小: $([math]::Round($FileSize, 2)) KB, 路径: $BackupGzFile"

    # 清理旧备份
    Write-Log "清理 $RetentionDays 天前的旧备份文件..."
    $LimitDate = (Get-Date).AddDays(-$RetentionDays)
    $OldFiles = Get-ChildItem -Path $BackupDir -Filter "shop_${DbName}_*.sql.gz" | Where-Object { $_.LastWriteTime -lt $LimitDate }
    foreach ($File in $OldFiles) {
        Remove-Item -Path $File.FullName -Force
        Write-Log "已清理旧文件: $($File.Name)"
    }

    Write-Log "====== 数据库备份与维护任务执行完毕 ======"
} catch {
    $ErrMsg = $_.Exception.Message
    Write-Log "[ERROR] 备份异常: $ErrMsg"
    if (Test-Path $DumpErrorFile) {
        Get-Content $DumpErrorFile -Tail 20 -ErrorAction SilentlyContinue | ForEach-Object { Write-Log "  $_" }
    }
    Send-Alarm $ErrMsg
    exit 1
} finally {
    # 无论成功失败都清理临时 SQL、错误日志和凭据文件。
    foreach ($tempFile in @($BackupSqlFile, $DumpErrorFile, $MySqlDefaultsFile)) {
        if ($tempFile -and (Test-Path $tempFile)) {
            Remove-Item -Path $tempFile -Force -ErrorAction SilentlyContinue
        }
    }
}
