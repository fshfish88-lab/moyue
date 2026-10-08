param(
    [Parameter(Mandatory=$true)][string]$Version,
    [Parameter(Mandatory=$true)][string]$DeliveryDirectory,
    [Parameter(Mandatory=$true)][string]$NotesPath,
    [string]$BuildTools = 'D:\Android Studio\Sdk\build-tools\36.0.0',
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Use a stable x.y.z version.' }
$delivery = (Resolve-Path -LiteralPath $DeliveryDirectory).Path
$notesFile = (Resolve-Path -LiteralPath $NotesPath).Path
$apk = Join-Path $delivery "Moyue-$Version.apk"
$source = Join-Path $delivery "Moyue-$Version-source.zip"
foreach ($file in @($apk,$source)) { if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing $file" } }
$metadata = @(& (Join-Path $BuildTools 'aapt2.exe') dump badging $apk)
if ($LASTEXITCODE -ne 0) { throw 'APK metadata failed.' }
$packageLine = $metadata | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
if ($packageLine -notmatch "name='com.moyue.reader' versionCode='(\d+)' versionName='([^']+)'") { throw 'Wrong APK package.' }
$versionCode = [long]$Matches[1]
if ($Matches[2] -ne $Version) { throw 'APK version differs from requested release.' }
$sdkLine = $metadata | Where-Object { $_ -like 'sdkVersion:*' } | Select-Object -First 1
if ($sdkLine -notmatch "sdkVersion:'(\d+)'") { throw 'Missing min SDK.' }
$minSdk = [int]$Matches[1]
$signature = @(& (Join-Path $BuildTools 'apksigner.bat') verify --print-certs $apk)
if ($LASTEXITCODE -ne 0 -or !(($signature -join "`n") -match 'certificate SHA-256 digest: 16f17d6aab70b30f11acb6ff48dbb00f44cfbe1844ef14c2d045ef3d9b25b72a')) { throw 'Wrong release signing certificate.' }
& (Join-Path $BuildTools 'zipalign.exe') -c 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
$notes = [IO.File]::ReadAllText($notesFile)
$updatePath = Join-Path $delivery 'update.json'
$manifest = [ordered]@{
    schemaVersion=1; packageName='com.moyue.reader'; versionName=$Version; versionCode=$versionCode; minSdk=$minSdk
    apkUrl="https://github.com/fshfish88-lab/moyue/releases/download/v$Version/Moyue-$Version.apk"
    sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    size=(Get-Item -LiteralPath $apk).Length; notes=$notes.Substring(0,[Math]::Min(6000,$notes.Length))
}
[IO.File]::WriteAllText($updatePath,($manifest | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$shaPath = Join-Path $delivery 'SHA256.txt'
$hashes = foreach ($file in @($apk,$source,$updatePath)) { (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash+'  '+[IO.Path]::GetFileName($file) }
[IO.File]::WriteAllText($shaPath,($hashes -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$repo = 'fshfish88-lab/moyue'
$root = Split-Path -Parent $PSScriptRoot
$commit = git -c "safe.directory=$($root.Replace('\','/'))" -C $root rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Git commit missing.' }
$remote = gh api "repos/$repo/commits/main" --jq '.sha'
if ($LASTEXITCODE -ne 0 -or $remote -ne $commit) { throw 'Push main and verify it before publishing.' }
$tag = "v$Version"
$releaseJson = gh release view $tag --repo $repo --json isDraft 2>$null
if ($LASTEXITCODE -eq 0) {
    if (!(($releaseJson | ConvertFrom-Json).isDraft)) { throw 'Published releases are immutable; use a new version.' }
    gh release edit $tag --repo $repo --target $commit --title "墨阅 V$Version" --notes-file $notesFile
} else {
    gh release create $tag --repo $repo --draft --target $commit --title "墨阅 V$Version" --notes-file $notesFile
}
if ($LASTEXITCODE -ne 0) { throw 'Release draft failed.' }
gh release upload $tag $apk $source $updatePath $shaPath --repo $repo --clobber
if ($LASTEXITCODE -ne 0) { throw 'Asset upload failed; draft remains unpublished.' }
$release = gh api "repos/$repo/releases" | ConvertFrom-Json | Where-Object { $_.tag_name -eq $tag } | Select-Object -First 1
if ($LASTEXITCODE -ne 0 -or !$release -or $release.target_commitish -ne $commit) { throw 'Draft target mismatch.' }
foreach ($file in @($apk,$source,$updatePath,$shaPath)) {
    $asset = @($release.assets | Where-Object { $_.name -eq [IO.Path]::GetFileName($file) })
    $digest = 'sha256:'+(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($asset.Count -ne 1 -or $asset[0].state -ne 'uploaded' -or $asset[0].digest -ne $digest) { throw "Remote digest mismatch: $file" }
}
if ($Publish) {
    gh release edit $tag --repo $repo --draft=false --latest
    if ($LASTEXITCODE -ne 0) { throw 'Publish failed.' }
    $latest = gh api "repos/$repo/releases/latest" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or $latest.tag_name -ne $tag -or $latest.draft) { throw 'Latest verification failed.' }
    Write-Output $latest.html_url
} else { Write-Output 'Verified draft created. Publish after completing acceptance checks.' }
