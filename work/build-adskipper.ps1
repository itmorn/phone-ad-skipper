$ErrorActionPreference = 'Stop'
$base = Join-Path $PSScriptRoot 'adskipper-android'
$sdk = Get-ChildItem "$PSScriptRoot\gallery-platform" -Filter android.jar -Recurse | Select-Object -First 1
$aapt = Get-ChildItem "$PSScriptRoot\gallery-build-tools" -Filter aapt2.exe -Recurse | Select-Object -First 1
if (-not $sdk -or -not $aapt) { throw 'Android platform or build tools missing' }
$tools = $aapt.Directory.FullName
$classes = Join-Path $base 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$javaSources = (Get-ChildItem "$base\src" -Filter '*.java').FullName
& javac -encoding UTF-8 --release 8 -classpath $sdk.FullName -d $classes $javaSources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
$classJar = Join-Path $base 'classes.jar'
& jar cf $classJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
& "$tools\d8.bat" --min-api 29 --lib $sdk.FullName --output $base $classJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Push-Location -LiteralPath $base
try {
    & $aapt.FullName compile --dir res -o resources.zip
    if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed' }
    $ruleDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'outputs\拦截规则'
    $assets = Join-Path $base 'rule-assets'
    New-Item -ItemType Directory -Force $assets | Out-Null
    $ruleAssets = @{
        '通用规则.json' = 'common.json'
        '百度网盘.json' = 'com.baidu.netdisk.json'
        '滴滴出行.json' = 'com.sdu.didi.psnger.json'
        '铁路12306.json' = 'com.MobileTicket.json'
        '中国移动.json' = 'com.greenpoint.android.mc10086.activity.json'
        '智行火车票.json' = 'com.yipiao.json'
        '携程旅行.json' = 'ctrip.android.view.json'
        '小米应用商店.json' = 'com.xiaomi.market.json'
    }
    foreach ($ruleFile in $ruleAssets.Keys) {
        Copy-Item -LiteralPath (Join-Path $ruleDirectory $ruleFile) -Destination (Join-Path $assets $ruleAssets[$ruleFile]) -Force
    }
    $sdkPath = [IO.Path]::GetRelativePath($base, $sdk.FullName)
    & $aapt.FullName link --auto-add-overlay -I $sdkPath --manifest AndroidManifest.xml -R resources.zip -A rule-assets -o unsigned.apk
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 failed' }
    & jar uf "$base\unsigned.apk" -C $base classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'Could not package dex' }
    & "$tools\zipalign.exe" -f 4 unsigned.apk aligned.apk
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
    $key = Join-Path $base 'adskipper-signing.p12'
    $passfile = Join-Path $base 'adskipper-signing-pass.txt'
    if (-not (Test-Path -LiteralPath $key)) {
        $seed = New-Object byte[] 32
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        $random.GetBytes($seed)
        $random.Dispose()
        $env:ADSKIPPER_APK_SIGNER = [Convert]::ToBase64String($seed)
        Set-Content -LiteralPath $passfile -Value $env:ADSKIPPER_APK_SIGNER -NoNewline -Encoding ascii
        & keytool -genkeypair -keyalg RSA -keysize 2048 -validity 3650 -alias adskipper -dname 'CN=Local Ad Skipper' -storetype PKCS12 -keystore $key '-storepass:env' ADSKIPPER_APK_SIGNER
        if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
    } else {
        $env:ADSKIPPER_APK_SIGNER = (Get-Content -Raw -LiteralPath $passfile).Trim()
    }
    $apk = Join-Path (Split-Path -Parent $PSScriptRoot) 'outputs\phone-adskipper.apk'
    & "$tools\apksigner.bat" sign --v4-signing-enabled false --ks $key --ks-pass env:ADSKIPPER_APK_SIGNER --out $apk "$base\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
    & "$tools\apksigner.bat" verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK verification failed' }
    & "$tools\aapt.exe" dump badging ../../outputs/phone-adskipper.apk | Select-Object -First 6
} finally {
    Pop-Location
}
