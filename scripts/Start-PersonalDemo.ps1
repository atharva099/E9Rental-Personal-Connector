param(
    [Parameter(Mandatory=$true)][string]$PrivateDirectory,
    [Parameter(Mandatory=$true)][string]$ClientFile,
    [Parameter(Mandatory=$true)][string]$ApplicationJar,
    [switch]$ApprovePersonalGoogleAccess
)

$ErrorActionPreference = 'Stop'

function Assert-DemoPortFree([int]$Port) {
    # Bind exclusively: never take over or terminate an existing listener.
    $probe = New-Object System.Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Port)
    $probe.Server.ExclusiveAddressUse = $true
    try { $probe.Start() }
    catch { throw "Port $Port is unavailable. Close its existing service yourself before starting this demo." }
    finally { $probe.Stop() }
}

function Initialize-DemoProcessJob {
    if (-not ('PersonalDemoProcessJob' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class PersonalDemoProcessJob {
    [StructLayout(LayoutKind.Sequential)] struct Basic {
        public long ProcessTime, JobTime;
        public uint Flags;
        public UIntPtr MinWorkingSet, MaxWorkingSet;
        public uint ActiveProcesses;
        public UIntPtr Affinity;
        public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] struct Io {
        public ulong ReadOps, WriteOps, OtherOps, ReadBytes, WriteBytes, OtherBytes;
    }
    [StructLayout(LayoutKind.Sequential)] struct Limits {
        public Basic Basic; public Io Io;
        public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool SetInformationJobObject(IntPtr job, int info, ref Limits limits, uint size);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    public static IntPtr Create() {
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero) throw new Win32Exception();
        Limits limits = new Limits(); limits.Basic.Flags = 0x2000; // KILL_ON_JOB_CLOSE
        if (!SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf(typeof(Limits)))) {
            int error = Marshal.GetLastWin32Error(); CloseHandle(job); throw new Win32Exception(error);
        }
        return job;
    }
    public static void Attach(IntPtr job, IntPtr process) {
        if (!AssignProcessToJobObject(job, process)) throw new Win32Exception();
    }
    public static void Close(IntPtr job) { if (job != IntPtr.Zero) CloseHandle(job); }
    public static bool Contains(IntPtr job, IntPtr process) {
        bool result;
        if (!IsProcessInJob(process, job, out result)) throw new Win32Exception();
        return result;
    }
}
'@
    }
}

function Start-DemoChild([IntPtr]$Job, [string]$Command, [string]$WorkingDirectory) {
    # The child waits until it belongs to our job. It cannot launch Java/npm first.
    $gateName = 'Local\E9PersonalDemo-' + [Guid]::NewGuid().ToString('N')
    $gate = New-Object Threading.EventWaitHandle($false, [Threading.EventResetMode]::ManualReset, $gateName)
    $ready = New-Object Threading.EventWaitHandle($false, [Threading.EventResetMode]::ManualReset, ($gateName + '-ready'))
    $child = $null
    try {
        $bootstrap = '$ErrorActionPreference=''Stop''; $gate=[Threading.EventWaitHandle]::OpenExisting(''' + $gateName + '''); $ready=[Threading.EventWaitHandle]::OpenExisting(''' + $gateName + '-ready''); [void]$ready.Set(); $ready.Dispose(); try { if(-not $gate.WaitOne(15000)){exit 1} } finally {$gate.Dispose()}; ' + $Command
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
        $info.Arguments = '-NoProfile -NonInteractive -OutputFormat Text -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($bootstrap))
        $info.WorkingDirectory = $WorkingDirectory
        $info.UseShellExecute = $false
        # Only the launcher reads keyboard input. In particular, Vite must not
        # consume Q and exit before the launcher recognizes a requested stop.
        # Keep this pipe open until cleanup; EOF can itself stop a child service.
        $info.RedirectStandardInput = $true
        # Inherited output goes to this console; secrets are never command arguments.
        $child = [Diagnostics.Process]::Start($info)
        [PersonalDemoProcessJob]::Attach($Job, $child.Handle)
        if (-not $ready.WaitOne(10000)) { throw 'Child startup handshake failed.' }
        [void]$gate.Set()
        return $child
    } catch {
        if ($child -and -not $child.HasExited) { $child.Kill(); $child.WaitForExit() }
        if ($child) { $child.Dispose() }
        throw 'Could not contain the demo process safely; startup stopped.'
    } finally { $gate.Dispose(); $ready.Dispose() }
}

function Wait-DemoEndpoint([Diagnostics.Process]$Child, [IntPtr]$Job, [int]$Port, [string]$Path) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Child.HasExited) { throw "Service on port $Port exited before readiness. Review its error above; do not initialize or repair the runtime." }
        $healthy = $false
        $response = $null
        try {
            $request = [Net.HttpWebRequest]::Create("http://127.0.0.1:$Port" + $Path)
            $request.Proxy = $null
            $request.AllowAutoRedirect = $false
            $request.Timeout = 2000
            $request.ReadWriteTimeout = 2000
            $response = $request.GetResponse()
            $healthy = [int]$response.StatusCode -eq 200
        } catch { }
        finally { if ($response) { $response.Close() } }
        if ($healthy) {
            # Filter the snapshot locally: a filtered CIM query can report no matches
            # as ObjectNotFound rather than returning an empty collection.
            try {
                $listeners = @(Get-NetTCPConnection -ErrorAction Stop | Where-Object {
                    $_.State -eq 'Listen' -and $_.LocalPort -eq $Port -and $_.LocalAddress -eq '127.0.0.1'
                })
            } catch {
                if ($_.CategoryInfo.Category -ne [Management.Automation.ErrorCategory]::ObjectNotFound) {
                    throw "Cannot inspect TCP ownership on port $Port; startup stopped safely."
                }
                $listeners = @()
            }
            if (-not $listeners.Count) {
                # HTTP 200 alone is insufficient. Retry ownership discovery within
                # the original readiness deadline, never assume this service is ours.
                Start-Sleep -Milliseconds 250
                continue
            }
            foreach ($listener in $listeners) {
                $owner = [Diagnostics.Process]::GetProcessById($listener.OwningProcess)
                try { if (-not [PersonalDemoProcessJob]::Contains($Job, $owner.Handle)) { throw "Port $Port belongs to a different process; startup stopped." } }
                finally { $owner.Dispose() }
            }
            return
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Service on port $Port was not ready within 90 seconds. Demo processes will be stopped; runtime will not be repaired."
}

function Get-DemoJavaVersion {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = (Get-Command java -ErrorAction Stop).Source
    $info.Arguments = '-version'
    $info.UseShellExecute = $false
    $info.RedirectStandardError = $true
    $info.RedirectStandardOutput = $true
    $info.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($info)
    try {
        $version = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'Could not check Java version.' }
        return $version
    } finally { $process.Dispose() }
}

function Read-DemoShutdownKey {
    if (-not [Console]::IsInputRedirected -and [Console]::KeyAvailable) {
        return [Console]::ReadKey($true)
    }
    return $null
}

function Wait-DemoServices([Diagnostics.Process]$Backend, [Diagnostics.Process]$Frontend) {
    $restoreControlC = $false
    $previousControlC = $false
    try {
        if (-not [Console]::IsInputRedirected) {
            $previousControlC = [Console]::TreatControlCAsInput
            [Console]::TreatControlCAsInput = $true
            $restoreControlC = $true
        }
        while ($true) {
            # Recognize a user stop before interpreting a concurrent child exit.
            $key = Read-DemoShutdownKey
            if ($null -ne $key -and ($key.Key -eq [ConsoleKey]::Q -or
                ($key.Key -eq [ConsoleKey]::C -and ($key.Modifiers -band [ConsoleModifiers]::Control)))) {
                return
            }
            if ($Backend.HasExited -or $Frontend.HasExited) { throw 'A demo service exited. Both demo process trees will be stopped; review the service error above.' }
            Start-Sleep -Milliseconds 500
        }
    } finally {
        if ($restoreControlC) { [Console]::TreatControlCAsInput = $previousControlC }
    }
}

function Invoke-PersonalDemo {
    $repository = Split-Path $PSScriptRoot -Parent
    if (-not $ApprovePersonalGoogleAccess) { throw 'Explicit -ApprovePersonalGoogleAccess is required.' }
    foreach ($name in @('PERSONAL_GMAIL_ACCOUNT','CHALLENGE_RUNTIME_DIR')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name,'Process'))) { throw "Set $name privately in this launch session first." }
    }
    foreach ($name in @('java','git','node','npm.cmd','Get-NetTCPConnection')) {
        if (-not (Get-Command $name -ErrorAction SilentlyContinue)) { throw "Required command missing: $name." }
    }
    $javaVersion = Get-DemoJavaVersion
    if ($javaVersion -notmatch 'version "21\.') { throw 'Java 21 is required.' }
    $nodeVersion = & node --version
    if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v24\.(\d+)\.(\d+)$' -or [int]$Matches[1] -lt 15) { throw 'Node.js 24.15 or later in the 24.x line is required.' }
    if (-not (Test-Path -LiteralPath $PrivateDirectory -PathType Container)) { throw 'Existing Personal runtime directory is missing; initialization is not supported by this launcher.' }
    foreach ($path in @($ClientFile,$ApplicationJar,(Join-Path $repository 'scripts/Start-Personal.ps1'),(Join-Path $repository 'frontend/node_modules/vite/bin/vite.js'))) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'A required client/JAR/launcher/frontend dependency is missing. Check supplied paths and install frontend dependencies separately with npm ci.' }
    }
    Assert-DemoPortFree 8083
    Assert-DemoPortFree 5173
    Initialize-DemoProcessJob
    $job = [PersonalDemoProcessJob]::Create()
    $backend = $null
    $frontend = $null
    try {
        # Quote only non-secret filesystem paths; account and keys remain in process environment.
        $quote = { param($value) "'" + $value.Replace("'", "''") + "'" }
        $backendCommand = '& ' + (& $quote (Join-Path $repository 'scripts/Start-Personal.ps1')) +
            ' -PrivateDirectory ' + (& $quote ([IO.Path]::GetFullPath($PrivateDirectory))) +
            ' -ClientFile ' + (& $quote ([IO.Path]::GetFullPath($ClientFile))) +
            ' -ApplicationJar ' + (& $quote ([IO.Path]::GetFullPath($ApplicationJar))) +
            ' -Port 8083 -ApprovePersonalGoogleAccess; exit $LASTEXITCODE'
        Write-Host 'Starting Personal backend on 127.0.0.1:8083 using existing-runtime validation. Drive remains disabled.'
        $backend = Start-DemoChild $job $backendCommand $repository
        Wait-DemoEndpoint $backend $job 8083 '/health'
        Write-Host 'Backend health passed. Starting frontend on 127.0.0.1:5173.'
        $frontendCommand = '$env:BACKEND_ORIGIN=''http://127.0.0.1:8083''; & npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort; exit $LASTEXITCODE'
        $frontend = Start-DemoChild $job $frontendCommand (Join-Path $repository 'frontend')
        Wait-DemoEndpoint $frontend $job 5173 '/'
        Write-Host 'Open http://127.0.0.1:5173 and connect with your local API token. OAuth consent is manual. When idle, press Q here to stop this demo (Ctrl+C also exits).'
        Wait-DemoServices $backend $frontend
    } finally {
        [PersonalDemoProcessJob]::Close($job)
        foreach ($child in @($backend,$frontend)) { if ($child) { $child.Dispose() } }
        Write-Host 'Demo process cleanup completed. Existing unrelated services were not targeted.'
    }
}

Invoke-PersonalDemo
