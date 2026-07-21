#Requires -Version 5.1
<#
.SYNOPSIS
  Build the PressScribe Android release APK and upload it to a GitHub release.

.PARAMETER Tag
  Git tag / release tag (required). Created if missing.

.PARAMETER Title
  Release title. Defaults to the tag.

.PARAMETER Notes
  Optional release notes body.

.PARAMETER SkipBuild
  Upload an existing APK without rebuilding.

.EXAMPLE
  .\upload-github-apk.ps1 -Tag android-v1.1.0 -Title "Android polish + translate"
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Tag,

    [string]$Title = "",

    [string]$Notes = "",

    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$androidRoot = Resolve-Path (Join-Path $scriptDir "..")
$repoRoot = Resolve-Path (Join-Path $androidRoot "..")
$assetName = "PressScribe-release.apk"
$gradleApk = Join-Path $androidRoot "app\build\outputs\apk\release\app-release.apk"
$stagedApk = Join-Path $androidRoot "app\build\outputs\apk\release\$assetName"

function Require-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

Require-Command "gh"
Require-Command "git"

Push-Location $androidRoot
try {
    if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
        $jdk17 = "C:\Program Files\Java\jdk-17"
        $studioJbr = "C:\Program Files\Android\Android Studio\jbr"
        if (Test-Path $jdk17) {
            $env:JAVA_HOME = $jdk17
        } elseif (Test-Path $studioJbr) {
            $env:JAVA_HOME = $studioJbr
        } else {
            throw "JAVA_HOME is not set and no JDK 17 / Android Studio JBR was found."
        }
    }
    $env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH

    if (-not $SkipBuild) {
        Write-Host "Building release APK..."
        & .\gradlew.bat :app:assembleRelease --quiet
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle assembleRelease failed with exit code $LASTEXITCODE"
        }
    }

    if (-not (Test-Path $gradleApk)) {
        throw "APK not found at $gradleApk. Build first or omit -SkipBuild."
    }

    Copy-Item -Path $gradleApk -Destination $stagedApk -Force
    Write-Host "Staged asset: $stagedApk"

    if ([string]::IsNullOrWhiteSpace($Title)) {
        $Title = $Tag
    }

    Push-Location $repoRoot
    try {
        $existing = gh release view $Tag 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Uploading $assetName to existing release $Tag ..."
            gh release upload $Tag $stagedApk --clobber
        } else {
            Write-Host "Creating release $Tag and uploading $assetName ..."
            $args = @("release", "create", $Tag, $stagedApk, "--title", $Title)
            if (-not [string]::IsNullOrWhiteSpace($Notes)) {
                $args += @("--notes", $Notes)
            } else {
                $args += "--generate-notes"
            }
            & gh @args
        }
        if ($LASTEXITCODE -ne 0) {
            throw "gh release command failed with exit code $LASTEXITCODE"
        }

        $url = gh release view $Tag --json url -q .url
        Write-Host "Done: $url"
        Write-Host "Asset: $assetName"
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
