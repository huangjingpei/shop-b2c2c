<#
.SYNOPSIS
    GMall Windows / PowerShell 环境 MySQL 数据库定时冷备与归档脚本
.DESCRIPTION
    支持全量事务级一致性导出、Gzip 压缩归档、完整性检查、30天自动清理及失败告警
#>

param (
    [string]$DbHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { "127.0.0.1" }),
    [int]$DbPort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 3306 }),
    [string]$DbName = $(if ($env:DB_NAME) { $env:DB_NAME } else { "shop_chain_db" }),
    [string]$DbUser = $(if ($env:DB_USER) { $env:DB_USER } else { "root" }),
    [string]$DbPass = $(if ($env:DB_PASS) { $env:DB_PASS } else { "SecretRoot123!" }),
    [string]$BackupDir = $(if ($env:BACKUP_DIR) { $env:BACKUP_DIR } else { "E:\gmall\backups\mysql" }),
    [int]$RetentionDays = 30,
    [string]$AlarmWebhook = $env:ALARM_WEBHOOK_URL
)

$ErrorActionPreference = "Stop"
$DateStr = Get-Date -Format "yyyyMMdd_HHmmss"
$BackupSqlFile = Join-Path $BackupDir "gmall_${DbName}_${DateStr}.sql"
$BackupGzFile = "$BackupSqlFile.gz"
$LogFile = Join-Path $BackupDir "backup.log"

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
                content = "### 🚨 数据库备份失败告警 (Windows)`n> 错误: $ErrorMessage`n> 时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
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
    # 优先检测本地 mysqldump 或 docker 容器
    $HasMysqldump = (Get-Command mysqldump -ErrorAction SilentlyContinue)
    if ($HasMysqldump) {
        $dumpArgs = @(
            "-h$DbHost",
            "-P$DbPort",
            "-u$DbUser",
            "--password=$DbPass",
            "--single-transaction",
            "--quick",
            "--default-character-set=utf8mb4",
            "--routines",
            "--triggers",
            $DbName
        )
        & mysqldump @dumpArgs > $BackupSqlFile
    } else {
        # 检测 Docker
        $DockerRunning = (docker ps --format "{{.Names}}" 2>$null | Select-String "gmall-mysql")
        if ($DockerRunning) {
            Write-Log "检测到 Docker 容器 gmall-mysql，通过容器内导出..."
            docker exec -i gmall-mysql mysqldump -u$DbUser -p$DbPass --single-transaction --quick --default-character-set=utf8mb4 --routines --triggers $DbName > $BackupSqlFile
        } else {
            throw "未找到 mysqldump 可执行文件或运行中的 gmall-mysql 容器"
        }
    }

    if (-not (Test-Path $BackupSqlFile) -or (Get-Item $BackupSqlFile).Length -eq 0) {
        throw "导出的 SQL 文件为空或不存在"
    }

    # 压缩为 .gz 归档
    Write-Log "执行 Gzip 流式压缩..."
    $inputBytes = [System.IO.File]::ReadAllBytes($BackupSqlFile)
    $outputFileStream = [System.IO.File]::Create($BackupGzFile)
    $gzipStream = New-Object System.IO.Compression.GZipStream($outputFileStream, [System.IO.Compression.CompressionMode]::Compress)
    $gzipStream.Write($inputBytes, 0, $inputBytes.Length)
    $gzipStream.Close()
    $outputFileStream.Close()

    # 删除未压缩的临时 SQL 文件
    Remove-Item -Path $BackupSqlFile -Force

    $FileSize = (Get-Item $BackupGzFile).Length / 1KB
    Write-Log "✅ 备份成功！大小: $([math]::Round($FileSize, 2)) KB, 路径: $BackupGzFile"

    # 清理旧备份
    Write-Log "清理 $RetentionDays 天前的旧备份文件..."
    $LimitDate = (Get-Date).AddDays(-$RetentionDays)
    $OldFiles = Get-ChildItem -Path $BackupDir -Filter "gmall_${DbName}_*.sql.gz" | Where-Object { $_.LastWriteTime -lt $LimitDate }
    foreach ($File in $OldFiles) {
        Remove-Item -Path $File.FullName -Force
        Write-Log "已清理旧文件: $($File.Name)"
    }

    Write-Log "====== 数据库备份与维护任务执行完毕 ======"
} catch {
    $ErrMsg = $_.Exception.Message
    Write-Log "❌ 备份异常: $ErrMsg"
    Send-Alarm $ErrMsg
    exit 1
}
