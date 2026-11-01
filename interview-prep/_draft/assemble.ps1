$ErrorActionPreference = "Stop"
$dir = "D:\interview-guide\interview-prep\_draft"
$out = "D:\interview-guide\interview-prep\01-项目全流程详解.md"

# 1) 备份原文件（只备份一次）
$backup = Join-Path $dir "_backup-01-original.md"
if (-not (Test-Path $backup)) {
  Copy-Item $out $backup
  Write-Host "已备份原文件 -> $backup"
}

# 2) 按最终顺序拼接草稿
$order = @(
  "00-头部与〇章.md",
  "F-第一章-项目整体介绍.md",
  "CH2-章首.md",
  "A1-技术选型-核心栈.md",
  "A2-技术选型-实时语音技术.md",
  "A3-技术选型-工程基础设施.md",
  "H-2.19其他选型速答表.md",
  "G-第三章-制作流程.md",
  "CH4-章首.md",
  "B-模块-出题与评估.md",
  "C-模块-语音链路.md",
  "D-模块-RAG.md",
  "CH5-章首.md",
  "E-第五章-重难点.md",
  "I-第六章-性能与稳定性.md"
)

$missing = @()
foreach ($f in $order) {
  if (-not (Test-Path (Join-Path $dir $f))) { $missing += $f }
}
if ($missing.Count -gt 0) {
  Write-Host "缺少以下草稿，未执行拼接："
  $missing | ForEach-Object { Write-Host "  - $_" }
  exit 1
}

$sb = New-Object System.Text.StringBuilder
foreach ($f in $order) {
  $p = Join-Path $dir $f
  $c = [System.IO.File]::ReadAllText($p)
  [void]$sb.Append($c.TrimEnd("`r", "`n"))
  [void]$sb.Append("`n`n")
  Write-Host ("  {0,-42} {1,7} chars" -f $f, $c.Length)
}

# 3) 保留原文件的第七、八章
$orig = [System.IO.File]::ReadAllText($out)
$marker = "## 七、个人贡献与项目亮点"
$idx = $orig.IndexOf($marker)
if ($idx -lt 0) { Write-Host "未找到第七章标记，中止"; exit 1 }
[void]$sb.Append($orig.Substring($idx).TrimEnd("`r", "`n"))
[void]$sb.Append("`n")

# 4) 以 LF 无 BOM 写出
$text = $sb.ToString()
$enc = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($out, $text, $enc)

$lf = ([regex]::Matches($text, "`n")).Count
$crlf = ([regex]::Matches($text, "`r`n")).Count
$loneCr = ([regex]::Matches($text, "`r(?!`n)")).Count
Write-Host ""
Write-Host "写入完成: $out"
Write-Host "总行数(LF)=$lf  CRLF=$crlf  loneCR=$loneCr  字符数=$($text.Length)"
