param([ValidateRange(1024,65535)][int]$Port=8818,[switch]$CheckOnly,[switch]$NoBrowser,[switch]$Offline,[switch]$Rebuild,[switch]$PrepareOnly)
$ErrorActionPreference='Stop'
# IDE可能继承PowerShell 7的模块路径；优先使用当前宿主自带模块。
$nativeModules=[IO.Path]::Combine($PSHOME,'Modules')
$env:PSModulePath=$nativeModules+';'+$env:PSModulePath
Import-Module ([IO.Path]::Combine($nativeModules,'Microsoft.PowerShell.Utility','Microsoft.PowerShell.Utility.psd1')) -Force
Import-Module ([IO.Path]::Combine($nativeModules,'Microsoft.PowerShell.Management','Microsoft.PowerShell.Management.psd1')) -Force
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
$projectRoot=$PSScriptRoot
Set-Location -LiteralPath $projectRoot
$browserJob=$null
function Resolve-ProjectPath([string]$Value){
 $full=if([IO.Path]::IsPathRooted($Value)){[IO.Path]::GetFullPath($Value)}else{[IO.Path]::GetFullPath((Join-Path $projectRoot $Value))}
 if($full.Length -gt [IO.Path]::GetPathRoot($full).Length){$full=$full.TrimEnd([char[]]@('\','/'))}
 return $full
}
function Read-Health {
 try{
  $request=[Net.HttpWebRequest]::Create("http://127.0.0.1:$Port/api/health");$request.Timeout=1200;$request.ReadWriteTimeout=1200;$request.Proxy=$null
  try{$response=$request.GetResponse()}catch [Net.WebException]{$response=$_.Exception.Response;if($null -eq $response){return $null}}
  try{$reader=New-Object IO.StreamReader($response.GetResponseStream());try{return ($reader.ReadToEnd()|ConvertFrom-Json)}finally{$reader.Dispose()}}finally{$response.Dispose()}
 }catch{return $null}
}
function Test-Port {
 $client=New-Object Net.Sockets.TcpClient
 try{$task=$client.ConnectAsync('127.0.0.1',$Port);return ($task.Wait(700) -and $client.Connected)}catch{return $false}finally{$client.Dispose()}
}
function Get-Jdk {
 $candidates=New-Object 'System.Collections.Generic.List[string]'
 if($env:JAVA_HOME){$candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe'))}
 foreach($name in @('javac.exe','java.exe')){$found=Get-Command $name -ErrorAction SilentlyContinue;if($found){$candidates.Add((Join-Path (Split-Path $found.Source) 'java.exe'))}}
 foreach($parent in @((Join-Path $projectRoot 'runtime'),(Join-Path $env:ProgramFiles 'Eclipse Adoptium'),(Join-Path $env:ProgramFiles 'Microsoft'),(Join-Path $env:ProgramFiles 'Java'))){
  if(Test-Path -LiteralPath $parent){foreach($folder in (Get-ChildItem -LiteralPath $parent -Directory)){$candidates.Add((Join-Path $folder.FullName 'bin\java.exe'))}}
 }
 foreach($key in @('HKLM:\SOFTWARE\JavaSoft\JDK','HKLM:\SOFTWARE\JavaSoft\Java Development Kit','HKLM:\SOFTWARE\Microsoft\JDK')){
  if(Test-Path $key){foreach($sub in (Get-ChildItem $key)){$value=Get-ItemProperty $sub.PSPath;if($value.JavaHome){$candidates.Add((Join-Path $value.JavaHome 'bin\java.exe'))}}}
 }
 foreach($candidate in ($candidates|Select-Object -Unique)){
  if(-not (Test-Path -LiteralPath $candidate)){continue}
  try{
   $probe=New-Object Diagnostics.ProcessStartInfo;$probe.FileName=$candidate;$probe.Arguments='-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -XshowSettings:properties -version';$probe.StandardErrorEncoding=[Text.Encoding]::UTF8;$probe.UseShellExecute=$false;$probe.CreateNoWindow=$true;$probe.RedirectStandardError=$true
   $process=[Diagnostics.Process]::Start($probe);$detail=$process.StandardError.ReadToEnd();$process.WaitForExit()
   $h=[regex]::Match($detail,'(?m)^\s*java.home\s*=\s*(.+)$');$v=[regex]::Match($detail,'(?m)^\s*java.specification.version\s*=\s*(\d+)(?:\.(\d+))?')
   if(-not $h.Success -or -not $v.Success -or $process.ExitCode -ne 0){$process.Dispose();continue}
   $process.Dispose();$jdkRoot=$h.Groups[1].Value.Trim();$major=[int]$v.Groups[1].Value;if($major -eq 1){$major=[int]$v.Groups[2].Value}
   if($major -ge 17 -and $major -le 25 -and (Test-Path -LiteralPath (Join-Path $jdkRoot 'bin\javac.exe'))){return @{Root=$jdkRoot;Java=(Join-Path $jdkRoot 'bin\java.exe');Version=$major}}
  }catch{continue}
 }
 throw '未找到完整 JDK 17～25（建议17或21）。请安装JDK并勾选 JAVA_HOME/PATH，或把解压版放到 runtime\jdk\。安装说明见 部署文档.md。'
}
function Assert-Writable([string]$Directory){
 try{New-Item -ItemType Directory -Force -Path $Directory|Out-Null;$probeFile=Join-Path $Directory ('.roam-write-check-'+[guid]::NewGuid().ToString('N')+'.tmp');[IO.File]::WriteAllText($probeFile,'check');Remove-Item -LiteralPath $probeFile}
 catch{throw "目录无法写入：$Directory 。请完整解压到可写文件夹，不要在压缩包内部直接启动。"}
}
function Get-Fingerprint {
 $files=@((Get-Item -LiteralPath (Join-Path $projectRoot 'pom.xml')))+(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src') -Recurse -File)
 $files+=@(Get-Item -LiteralPath (Join-Path $projectRoot 'sql\guide.db'));$files+=@(Get-Item -LiteralPath (Join-Path $projectRoot 'sql\demo-manifest.json'))
 $paths=New-Object 'System.Collections.Generic.List[string]'
 foreach($file in $files){$paths.Add($file.FullName)}
 $paths.Sort([StringComparer]::Ordinal)
 $text=New-Object Text.StringBuilder
 foreach($path in $paths){[void]$text.Append($path.Substring($projectRoot.Length).Replace('\','/')).Append(':').Append((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash).Append("`n")}
 $sha=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text.ToString())))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
}
try{
 Write-Host '漫游旅行指南：启动检查' -ForegroundColor Cyan
 foreach($name in @('pom.xml','src\main\resources\schema.sql','sql\guide.db','sql\demo-manifest.json')){if(-not (Test-Path -LiteralPath (Join-Path $projectRoot $name))){throw "缺少 $name 。请先完整解压源码，再运行 启动网站.cmd。"}}
 $dataRoot=if($env:ROAM_DATA_DIR){Resolve-ProjectPath $env:ROAM_DATA_DIR}else{Join-Path $projectRoot 'data'}
 $sha=[Security.Cryptography.SHA256]::Create();try{$instanceId=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes("roam-guide`n"+$dataRoot.Replace('/','\').ToLowerInvariant())))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
 if(Test-Port){
  $health=Read-Health
  if($health -and $health.application -eq 'roam-guide' -and $health.instance_id -eq $instanceId -and $health.status -eq 'ready'){
   Write-Host "当前数据目录的网站已在运行：http://127.0.0.1:$Port ，不会重复启动或覆盖数据。" -ForegroundColor Green
   if(-not $NoBrowser -and -not $CheckOnly -and -not $PrepareOnly){Start-Process "http://127.0.0.1:$Port"};exit 0
  }
  throw "端口 $Port 已被其他程序、另一份网站或尚未就绪的进程占用。请先关闭原窗口，或用 powershell -File .\run.ps1 -Port 8821 。不会停止原程序或覆盖其数据。"
 }
 $jdk=Get-Jdk;Write-Host ('Java检查通过：JDK '+$jdk.Version)
 $env:JAVA_HOME=$jdk.Root;$env:PATH=(Join-Path $jdk.Root 'bin')+';'+$env:PATH
 $env:MAVEN_OPTS=($env:MAVEN_OPTS+' -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8').Trim()
 $manifest=[IO.File]::ReadAllText((Join-Path $projectRoot 'sql\demo-manifest.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 if($manifest.application -ne 'roam-guide' -or (Get-FileHash -LiteralPath (Join-Path $projectRoot 'sql\guide.db') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.sha256){throw '演示数据校验失败。请重新解压源码；不要用运行数据库直接覆盖 sql\guide.db。'}
 Assert-Writable $projectRoot;Assert-Writable $dataRoot;$env:ROAM_DATA_DIR=$dataRoot
 $databaseFile=Join-Path $dataRoot 'guide.db'
 if(Test-Path -LiteralPath $databaseFile){
  $stream=[IO.File]::OpenRead($databaseFile);try{$header=New-Object byte[] 16;$length=$stream.Read($header,0,16)}finally{$stream.Dispose()}
  if($length -ne 16 -or [Text.Encoding]::ASCII.GetString($header) -ne "SQLite format 3`0"){throw '已有数据库为空或格式不正确。为避免覆盖，启动已停止；请先备份，再按部署文档排查。'}
  Write-Host '已有数据库：完整检查后继续使用，不导入演示数据。'
 }else{Write-Host '首次运行：自动准备完整演示数据，无需手动执行SQL。'}
 $cacheRoot=if($env:ROAM_TOOLS_DIR){Resolve-ProjectPath $env:ROAM_TOOLS_DIR}else{Join-Path $env:LOCALAPPDATA 'RoamGuide\tools'}
 $repository=if($env:ROAM_MAVEN_REPO){Resolve-ProjectPath $env:ROAM_MAVEN_REPO}else{Join-Path $cacheRoot 'repository'}
 if($CheckOnly){Write-Host "环境检查通过。地址：http://127.0.0.1:$Port 。首次编译需要联网，此后可使用缓存。" -ForegroundColor Green;exit 0}
 $jar=Join-Path $projectRoot 'target\roam-guide-0.1.0.jar';$stampPath=Join-Path $projectRoot 'target\build-state.json';$fingerprint=Get-Fingerprint;$reuse=$false
 if(-not $Rebuild -and (Test-Path -LiteralPath $jar) -and (Test-Path -LiteralPath $stampPath)){
  try{$stamp=[IO.File]::ReadAllText($stampPath)|ConvertFrom-Json;$reuse=($stamp.source -eq $fingerprint -and $stamp.jar -eq (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash)}catch{$reuse=$false}
 }
 if(-not $reuse){
  Assert-Writable $cacheRoot;Assert-Writable $repository;$mavenCommand=Join-Path $cacheRoot 'apache-maven-3.9.11\bin\mvn.cmd'
  if(-not (Test-Path -LiteralPath $mavenCommand)){
   if($Offline){throw '本机未准备Maven，无法首次离线编译。请先联网启动一次；不会修改已有数据库。'}
   $archive=Join-Path $cacheRoot 'apache-maven-3.9.11-bin.zip';$download=$archive+'.'+[guid]::NewGuid().ToString('N')+'.download'
   Write-Host '首次运行，正在从官方仓库准备编译工具，请保持联网。';[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
   try{
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' -OutFile $download -UseBasicParsing -TimeoutSec 180
    $expected='03E2D65D4483A3396980629F260E25CAC0D8B6F7F2791E4DC20BC83F9514DB8D0F05B0479E699A5F34679250C49C8E52E961262DED468A20DE0BE254D8207076'
    if((Get-FileHash -LiteralPath $download -Algorithm SHA512).Hash -ne $expected){throw '编译工具校验失败，请检查网络后重试。'}
    Move-Item -LiteralPath $download -Destination $archive -Force;Expand-Archive -LiteralPath $archive -DestinationPath $cacheRoot -Force
   }finally{if(Test-Path -LiteralPath $download){Remove-Item -LiteralPath $download}}
  }
  Write-Host '正在从完整源码编译。首次下载依赖可能需要几分钟；编译成功前不会初始化数据库。'
  $mavenArgs=@('-B','-ntp','-Dstyle.color=never',('-Dmaven.repo.local='+$repository),'package');if($Offline){$mavenArgs=@('-o')+$mavenArgs}
  & $mavenCommand @mavenArgs
  if($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $jar)){throw '编译未完成。首次运行请保持联网，下载失败可以稍后重试。已有数据库不会被初始化流程覆盖。'}
  @{source=$fingerprint;jar=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash}|ConvertTo-Json|Set-Content -LiteralPath $stampPath -Encoding UTF8
 }else{Write-Host '源码和运行包校验一致，直接使用本机编译结果。'}
 if($PrepareOnly){Write-Host '编译准备完成，尚未启动服务或创建数据库。' -ForegroundColor Green;exit 0}
 Write-Host "正在启动 http://127.0.0.1:$Port ，就绪后再打开浏览器；停止时请按 Ctrl+C。" -ForegroundColor Green
 if(-not $NoBrowser){
  $browserJob=Start-Job -ArgumentList $Port,$instanceId -ScriptBlock {
   param($ListenPort,$ExpectedInstance)
   for($attempt=0;$attempt -lt 120;$attempt++){
    try{$health=Invoke-RestMethod -Uri "http://127.0.0.1:$ListenPort/api/health" -TimeoutSec 1 -ErrorAction Stop;if($health.application -eq 'roam-guide' -and $health.instance_id -eq $ExpectedInstance -and $health.status -eq 'ready'){Start-Process "http://127.0.0.1:$ListenPort";return}}catch{}
    Start-Sleep -Milliseconds 500
   }
  }
 }
 & $jdk.Java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' '--enable-native-access=ALL-UNNAMED' '-jar' $jar ("--server.port="+$Port)
 if($LASTEXITCODE -ne 0){throw '网站未正常完成启动。请查看上方中文提示和 data\logs\runtime.log；不要直接删除或覆盖数据库。'}
}catch{Write-Host ('启动未完成：'+$_.Exception.Message) -ForegroundColor Red;Write-Host '请查看根目录的 部署文档.md；保留已有数据库。';exit 1}
finally{if($browserJob){Stop-Job -Job $browserJob -ErrorAction SilentlyContinue;Remove-Job -Job $browserJob -Force -ErrorAction SilentlyContinue}}
