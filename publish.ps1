# Builds a signed release, publishes it as a GitHub release, and points the update
# manifest at it. Run from the project root after bumping versionCode/versionName
# in app/build.gradle.kts.
#
#   ./publish.ps1 -Notes "What changed"

param(
    [string]$Notes = "Wartungsupdate."
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$gradleFile = "app/build.gradle.kts"
$content = Get-Content $gradleFile -Raw

if ($content -notmatch 'versionCode\s*=\s*(\d+)') { throw "versionCode not found in $gradleFile" }
$versionCode = [int]$Matches[1]
if ($content -notmatch 'versionName\s*=\s*"([^"]+)"') { throw "versionName not found in $gradleFile" }
$versionName = $Matches[1]

$tag = "v$versionName"
Write-Host "Publishing $tag (versionCode $versionCode)" -ForegroundColor Cyan

# A tag that already exists means the version was not bumped; publishing anyway would
# leave installed clients pointing at an APK they already have.
$existing = gh release view $tag 2>$null
if ($LASTEXITCODE -eq 0) { throw "Release $tag already exists. Bump versionCode/versionName first." }

& ./gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

Copy-Item "app/build/outputs/apk/release/app-release.apk" "GpsMock.apk" -Force

gh release create $tag "GpsMock.apk" --title $tag --notes $Notes
if ($LASTEXITCODE -ne 0) { throw "Release upload failed" }

$manifest = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    apkUrl      = "https://github.com/seltonmt012/gpsmock/releases/download/$tag/GpsMock.apk"
    notes       = $Notes
}
$manifest | ConvertTo-Json -Depth 3 | Set-Content "update.json" -Encoding UTF8

git add update.json app/build.gradle.kts
git commit -m "Release $tag"
git push

Write-Host "Published $tag. Clients see it within ~5 minutes (CDN cache)." -ForegroundColor Green
