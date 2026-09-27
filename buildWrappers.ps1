param(
    [ValidateSet("Android", "IOS", "All")]
    [string] $Target = "All",
    [switch] $NoBuild,
    [string] $PublicApk
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RuntimeSource = Join-Path $RepoRoot "CelesteRuntime"
$AndroidRuntimeDest = Join-Path $RepoRoot "AndroidWrapper\app\src\main\assets\CelesteRuntime"
$IOSRuntimeDest = Join-Path $RepoRoot "IOSWrapper\assets\CelesteRuntime"

function Assert-StagingPath([string] $Destination) {
    $Resolved = [IO.Path]::GetFullPath($Destination)
    if ($Resolved -notin @([IO.Path]::GetFullPath($AndroidRuntimeDest), [IO.Path]::GetFullPath($IOSRuntimeDest))) {
        throw "Refusing to change a path outside the wrapper staging directories: $Resolved"
    }
}

function Copy-Runtime {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Destination
    )

    if (-not (Test-Path -LiteralPath $RuntimeSource -PathType Container)) {
        throw "Missing runtime folder: $RuntimeSource"
    }

    Assert-StagingPath $Destination
    $DestinationParent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $DestinationParent | Out-Null

    if (Test-Path -LiteralPath $Destination) {
        Remove-Item -LiteralPath $Destination -Recurse -Force
    }

    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Get-ChildItem -LiteralPath $RuntimeSource -Recurse -File | Where-Object {
        $_.Name -notmatch '\.(bak.*|tmp|log|pdb)$' -and $_.FullName -notmatch '[\\/](bin|obj)[\\/]'
    } | ForEach-Object {
        $Relative = $_.FullName.Substring($RuntimeSource.Length + 1)
        $TargetFile = Join-Path $Destination $Relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $TargetFile) | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $TargetFile
    }
    Write-Host "Staged CelesteRuntime -> $Destination"
}

function Remove-StagedRuntime {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Destination
    )

    Assert-StagingPath $Destination
    if (Test-Path -LiteralPath $Destination) {
        Remove-Item -LiteralPath $Destination -Recurse -Force
        Write-Host "Removed staged CelesteRuntime from $Destination"
    }
}

function Build-Android {
    if ($NoBuild) {
        Copy-Runtime -Destination $AndroidRuntimeDest
        return
    }
    try {
        # Gradle stages the runtime itself; avoid a redundant full copy.
        $Gradle = Join-Path $RepoRoot "AndroidWrapper\gradlew.bat"
        if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) {
            throw "Missing Android Gradle wrapper: $Gradle"
        }

        Push-Location (Join-Path $RepoRoot "AndroidWrapper")
        try {
            & $Gradle --no-daemon :app:assembleDebug
            if ($LASTEXITCODE -ne 0) {
                throw "Android wrapper build failed with exit code $LASTEXITCODE"
            }
        } finally {
            Pop-Location
        }
    } finally {
        Remove-StagedRuntime -Destination $AndroidRuntimeDest
    }
}

function Build-IOS {
    $IOSRoot = Join-Path $RepoRoot "IOSWrapper"
    if ($PublicApk) {
        & python (Join-Path $IOSRoot "stage-assets.py") --apk $PublicApk
        if ($LASTEXITCODE -ne 0) { throw "Public iOS asset staging failed" }
    }
    if ($NoBuild) {
        if (-not $PublicApk) { throw "iOS staging requires -PublicApk pointing to the encrypted public APK" }
        return
    }
    if (-not (Get-Command xcodebuild -ErrorAction SilentlyContinue)) {
        throw "iOS source is in IOSWrapper. No IPA was built: macOS and Xcode are required. On a Mac run bash IOSWrapper/build.sh simulator (or device with signing configured)."
    }
    & bash (Join-Path $IOSRoot "build.sh") simulator
    if ($LASTEXITCODE -ne 0) { throw "iOS build failed" }
}

switch ($Target) {
    "Android" { Build-Android }
    "IOS" { Build-IOS }
    "All" {
        Build-Android
        Build-IOS
    }
}
