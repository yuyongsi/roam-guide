param([string]$DataDir, [string]$Destination, [string]$Verify)
$ErrorActionPreference='Stop'
$env:PSModulePath=[IO.Path]::Combine($PSHOME,'Modules')+';'+$env:PSModulePath
Import-Module ([IO.Path]::Combine($PSHOME,'Modules','Microsoft.PowerShell.Utility','Microsoft.PowerShell.Utility.psd1')) -Force
Import-Module ([IO.Path]::Combine($PSHOME,'Modules','Microsoft.PowerShell.Management','Microsoft.PowerShell.Management.psd1')) -Force
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Full([string]$p) { if (-not [IO.Path]::IsPathRooted($p)) {$p=Join-Path $root $p}; $full=[IO.Path]::GetFullPath($p); if ($full.Length -gt [IO.Path]::GetPathRoot($full).Length) {$full=$full.TrimEnd([char[]]@('\','/'))}; return $full }
function NoLinks([string]$p) {
 $item=Get-Item -LiteralPath $p -Force -ErrorAction Stop
 while ($item) { if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {throw "路径包含链接或重解析点：$($item.FullName)"}; if ($item -is [IO.FileInfo]) {$item=$item.Directory} else {$item=$item.Parent} }
}
function Files([string]$base) {
 NoLinks $base
 $all=@(Get-ChildItem -LiteralPath $base -Force -Recurse)
 foreach($i in $all) {if (($i.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {throw "数据含链接或重解析点：$($i.FullName)"}}
 return @($all | Where-Object {-not $_.PSIsContainer})
}
function Relative([string]$base,[string]$file) { return $file.Substring($base.Length+1).Replace('\','/') }
function Check([string]$backup) {
 $backup=Full $backup; NoLinks $backup
 $manifestPath=Join-Path $backup 'manifest.json'; $data=Join-Path $backup 'data'
 if (!(Test-Path -LiteralPath $manifestPath -PathType Leaf) -or !(Test-Path -LiteralPath $data -PathType Container)) {throw '缺少清单或数据目录，备份不完整。'}
 NoLinks $manifestPath
 $m=[IO.File]::ReadAllText($manifestPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
 if ($m.status -ne 'complete' -or $m.format -ne 'roam-data-backup-v1') {throw '清单状态或格式无效。'}
 $actual=@(Files $data); $entries=@($m.files)
 if ($actual.Count -ne $entries.Count) {throw '文件数量不符。'}
 $seen=@{}
 foreach($entry in $entries) {
  $rel=[string]$entry.path
  if ([string]::IsNullOrWhiteSpace($rel) -or $rel.Contains('\') -or $rel.StartsWith('/') -or ($rel -split '/' | Where-Object {$_ -eq '..' -or $_ -eq '.' -or $_ -eq ''}) -or $rel.Contains(':')) {throw '清单包含无效路径。'}
  if ($seen.ContainsKey($rel)) {throw '清单包含重复路径。'}; $seen[$rel]=$true
  $file=Join-Path $data $rel.Replace('/','\')
  if (!(Test-Path -LiteralPath $file -PathType Leaf)) {throw "文件缺失：$rel"}; NoLinks $file
  if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256 -or (Get-Item -LiteralPath $file).Length -ne [long]$entry.size) {throw "文件校验失败：$rel"}
 }
 Write-Host "备份校验通过：$backup" -ForegroundColor Green
}
try {
 if ($Verify) { Check $Verify; exit 0 }
 if (!$DataDir) {$DataDir=if($env:ROAM_DATA_DIR){$env:ROAM_DATA_DIR}else{'data'}}
 $source=Full $DataDir
 if ($source.Equals([IO.Path]::GetPathRoot($source),[StringComparison]::OrdinalIgnoreCase)) {throw '不能备份整个磁盘根目录。'}
 if (!(Test-Path -LiteralPath $source -PathType Container)) {throw "数据目录不存在：$source"}
 NoLinks $source
 if (!(Test-Path -LiteralPath (Join-Path $source 'guide.db') -PathType Leaf) -or (Get-Item -LiteralPath (Join-Path $source 'guide.db')).Length -eq 0) {throw '数据目录没有有效的 guide.db，未创建备份。'}
 if (!$Destination) {$Destination=Join-Path (Split-Path $root -Parent) '漫游旅行指南数据备份'}
 $dest=Full $Destination
 if ($dest.Equals([IO.Path]::GetPathRoot($dest),[StringComparison]::OrdinalIgnoreCase)) {throw '备份目标不能是磁盘根目录，请指定其中的文件夹。'}
 if ($dest.Equals($source,[StringComparison]::OrdinalIgnoreCase) -or $dest.StartsWith($source+'\',[StringComparison]::OrdinalIgnoreCase) -or $source.StartsWith($dest+'\',[StringComparison]::OrdinalIgnoreCase)) {throw '备份目录与数据目录不能互相包含。'}
 # Check every existing ancestor before creating the destination.
 $ancestor=$dest; while (!(Test-Path -LiteralPath $ancestor)) {$parent=Split-Path $ancestor -Parent; if (!$parent -or $parent -eq $ancestor) {throw '备份目录无效。'}; $ancestor=$parent}; NoLinks $ancestor
 $lockPath=Join-Path $source '.startup.lock'
 if (Test-Path -LiteralPath $lockPath) {NoLinks $lockPath}
 $lock=[IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
 try {
  try {$lock.Lock(0,[long]::MaxValue)} catch {throw '此数据目录正在被网站或其他备份使用，请先关闭网站后重试。'}
  try {
   $db=Join-Path $source 'guide.db'; $dbStream=[IO.File]::OpenRead($db)
   try { $header=New-Object byte[] 16; $n=$dbStream.Read($header,0,16); if ($n -ne 16 -or [Text.Encoding]::ASCII.GetString($header) -ne "SQLite format 3`0") {throw 'guide.db 不是有效的 SQLite 文件，未创建备份。'} } finally {$dbStream.Dispose()}
   $items=@(Files $source | Where-Object { -not $_.FullName.Equals($lockPath,[StringComparison]::OrdinalIgnoreCase) })
   if ($items.Count -eq 0) {throw '数据目录为空。'}
   [IO.Directory]::CreateDirectory($dest)|Out-Null; NoLinks $dest
   $name='备份-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
   $partial=Join-Path $dest ($name+'.未完成'); [IO.Directory]::CreateDirectory($partial)|Out-Null
   $copy=Join-Path $partial 'data'; [IO.Directory]::CreateDirectory($copy)|Out-Null
   foreach($dir in @(Get-ChildItem -LiteralPath $source -Force -Recurse -Directory)) { [IO.Directory]::CreateDirectory((Join-Path $copy (Relative $source $dir.FullName).Replace('/','\'))) | Out-Null }
   $entries=New-Object 'System.Collections.Generic.List[object]'
   foreach($file in $items) {
    $rel=Relative $source $file.FullName; $target=Join-Path $copy $rel.Replace('/','\'); $parent=Split-Path $target -Parent
    [IO.Directory]::CreateDirectory($parent)|Out-Null
    [IO.File]::Copy($file.FullName,$target,$false)
    $hash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {throw "复制校验失败：$rel"}
    $entries.Add([ordered]@{path=$rel;size=$file.Length;sha256=$hash})
   }
   $manifest=[ordered]@{format='roam-data-backup-v1';status='complete';scope='runtime data directory; excludes .startup.lock coordination file';created=(Get-Date).ToString('o');files=@($entries.ToArray())}
   [IO.File]::WriteAllText((Join-Path $partial 'manifest.json'),($manifest|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($true))
   Check $partial
   $final=Join-Path $dest $name; [IO.Directory]::Move($partial,$final)
   Write-Host "备份完成：$final" -ForegroundColor Green
  } finally {$lock.Unlock(0,[long]::MaxValue)}
 } finally {$lock.Dispose()}
} catch {Write-Host ('操作未完成：'+$_.Exception.Message) -ForegroundColor Red; if ($partial) {Write-Host "未完成目录保留供检查：$partial"}; exit 1}
