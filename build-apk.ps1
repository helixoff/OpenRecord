param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = Join-Path $PSScriptRoot '.tools/android-sdk' }
if (!$Jdk) { throw 'Set JAVA_HOME to a JDK 17+ installation, or pass -Jdk.' }
$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk/bin;$env:PATH"
$tools = Join-Path $Sdk 'build-tools/36.0.0'
$android = Join-Path $Sdk 'platforms/android-36/android.jar'
if (!(Test-Path $android)) { throw 'Install Android SDK platform 36 and build-tools 36.0.0 first.' }
$out = Join-Path $PSScriptRoot '.tools/apk-build'
$dist = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force $out, "$out/res", "$out/generated", "$out/classes", "$out/dex", $dist | Out-Null
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}
Run "$tools/aapt2.exe" @('compile', '--dir', "$PSScriptRoot/app/src/main/res", '-o', "$out/resources.zip")
# AGP normally injects the namespace; standalone aapt2 needs it in its input manifest.
$manifest = [IO.File]::ReadAllText("$PSScriptRoot/app/src/main/AndroidManifest.xml").Replace('<manifest ', '<manifest package="org.openrecord.app" android:versionCode="1" android:versionName="1.0.0" ')
$manifest = $manifest.Replace('<application ', '<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36" /><application ')
[IO.File]::WriteAllText("$out/AndroidManifest.xml", $manifest)
Run "$tools/aapt2.exe" @('link', '-o', "$out/base.apk", '-I', $android, '--manifest', "$out/AndroidManifest.xml", '--java', "$out/generated", "$out/resources.zip")
$sources = @(Get-ChildItem "$PSScriptRoot/app/src/main/java", "$out/generated" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
Run "$Jdk/bin/javac.exe" (@('-encoding', 'UTF-8', '--release', '17', '-classpath', $android, '-d', "$out/classes") + $sources)
Run "$Jdk/bin/jar.exe" @('--create', '--file', "$out/classes.jar", '-C', "$out/classes", '.')
Run "$tools/d8.bat" @('--min-api', '29', '--lib', $android, '--output', "$out/dex", "$out/classes.jar")
Copy-Item -LiteralPath "$out/base.apk" -Destination "$out/unsigned.apk" -Force
Run "$Jdk/bin/jar.exe" @('--update', '--file', "$out/unsigned.apk", '-C', "$out/dex", 'classes.dex')
Run "$tools/zipalign.exe" @('-f', '-p', '4', "$out/unsigned.apk", "$out/aligned.apk")
$key = Join-Path $PSScriptRoot '.tools/debug.keystore'
if (!(Test-Path $key)) {
    Run "$Jdk/bin/keytool.exe" @('-genkeypair', '-keystore', $key, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000')
}
Run "$tools/apksigner.bat" @('sign', '--ks', $key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', "$dist/OpenRecord.apk", "$out/aligned.apk")
Run "$tools/apksigner.bat" @('verify', '--verbose', "$dist/OpenRecord.apk")
Write-Host "APK ready: $dist/OpenRecord.apk"
