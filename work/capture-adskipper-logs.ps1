param(
    [string]$Serial = $env:ADSKIPPER_ADB_SERIAL
)
if (-not $Serial) {
    throw 'Specify an ADB device with -Serial or ADSKIPPER_ADB_SERIAL.'
}
$adb = Join-Path $PSScriptRoot 'adb-tools/platform-tools/adb.exe'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$directory = Join-Path (Split-Path -Parent $PSScriptRoot) 'outputs/手机免广告助手日志'
$request = Join-Path $directory '.clear-request'
$errorFile = Join-Path $directory '.clear-error'
New-Item -ItemType Directory -Force -Path $directory | Out-Null

function Open-Log {
    $stamp = Get-Date -Format 'yyyy-MM-dd_HH-mm-ss.fff'
    $path = Join-Path $directory "手机免广告助手_$stamp.log"
    $share = [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
    $stream = [IO.FileStream]::new($path, [IO.FileMode]::Append, [IO.FileAccess]::Write, $share)
    $output = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($true))
    $output.AutoFlush = $true
    $output.WriteLine("$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')  开始采集 ADB $serial")
    return $output
}

function Clear-CapturedFiles {
    foreach ($pattern in @('手机免广告助手_*.log', '控件快照_*.txt')) {
        foreach ($path in [IO.Directory]::GetFiles($directory, $pattern, [IO.SearchOption]::TopDirectoryOnly)) {
            [IO.File]::Delete($path)
        }
    }
}

$mutex = [Threading.Mutex]::new($false, 'Local\PhoneAdSkipperLogCapture')
$ownsMutex = $false
$writer = $null
$tree = $null
$logcat = $null
try {
    try { $ownsMutex = $mutex.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (!$ownsMutex) { exit }

    $writer = Open-Log
    $start = [Diagnostics.ProcessStartInfo]::new($adb)
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.StandardOutputEncoding = [Text.Encoding]::UTF8
    foreach ($argument in @('-s',$serial,'logcat','-b','main','-b','events','-v','threadtime','-T','1',
        'PhoneAdSkipper:I','AdSkipTree:I','wm_on_top_resumed_gained_called:I','am_kill:I','*:S')) {
        $start.ArgumentList.Add($argument)
    }
    $retryAt = [DateTime]::MinValue
    while ($true) {
        if ([IO.File]::Exists($request)) {
            if ($tree) { $tree.Dispose(); $tree = $null }
            $treeId = ''
            $writer.Dispose()
            try {
                Clear-CapturedFiles
                [IO.File]::Delete($errorFile)
            } catch {
                [IO.File]::WriteAllText($errorFile, $_.Exception.Message)
            } finally {
                $writer = Open-Log
                [IO.File]::Delete($request)
            }
        }
        if (![IO.File]::Exists($writer.BaseStream.Name)) {
            $writer.Dispose()
            $writer = Open-Log
        }
        if (!$logcat) {
            if ([DateTime]::UtcNow -lt $retryAt) {
                Start-Sleep -Milliseconds 200
                continue
            }
            & $adb connect $serial 2>&1 | ForEach-Object { $writer.WriteLine([string]$_) }
            $logcat = [Diagnostics.Process]::Start($start)
            $pending = $logcat.StandardOutput.ReadLineAsync()
        }
        if (!$pending.Wait(200)) { continue }
        $line = $pending.Result
        if ($null -eq $line) {
            $writer.WriteLine("$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')  ADB 日志流断开，稍后重连")
            $logcat.Dispose()
            $logcat = $null
            $retryAt = [DateTime]::UtcNow.AddSeconds(5)
            continue
        }
        $pending = $logcat.StandardOutput.ReadLineAsync()
        if ($line -match 'AdSkipTree\s*:\s+(\d{13}-\d+-\d+) (.*)$') {
            $id = $Matches[1]
            $body = $Matches[2]
            if ($body.StartsWith('BEGIN ')) {
                if ($tree) { $tree.Dispose() }
                $time = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$id.Split('-')[0]).ToLocalTime().ToString('yyyy-MM-dd_HH-mm-ss.fff')
                $tree = [IO.StreamWriter]::new((Join-Path $directory "控件快照_$time-$id.txt"), $false, [Text.UTF8Encoding]::new($true))
                $treeId = $id
            }
            if ($tree -and $id -eq $treeId) {
                $tree.WriteLine($line)
                if ($body.StartsWith('END ')) {
                    $tree.Dispose()
                    $tree = $null
                    $writer.WriteLine("控件快照 $id $body")
                }
            }
        } elseif ($line -match 'PhoneAdSkipper|logcat:|error:|device offline' -or
                ($line -match 'wm_on_top_resumed_gained_called' -and $line -match 'com\.baidu\.netdisk|local\.adskipper') -or
                ($line -match 'am_kill' -and $line -match 'local\.adskipper')) {
            $writer.WriteLine($line)
        }
    }
} finally {
    if ($tree) { $tree.Dispose() }
    if ($writer) { $writer.Dispose() }
    if ($logcat) {
        if (!$logcat.HasExited) { $logcat.Kill() }
        $logcat.Dispose()
    }
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
