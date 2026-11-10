$ErrorActionPreference = 'Stop'
$reportRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$htmlPath = Join-Path $reportRoot '实验报告.html'
if (-not (Test-Path -LiteralPath $htmlPath)) { throw 'Run analysis/render_report.py first.' }
$browserCandidates = @(
  (Join-Path ${env:ProgramFiles} 'Google\Chrome\Application\chrome.exe'),
  (Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe')
)
$browserExecutable = $browserCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $browserExecutable) { throw 'Chrome or Edge is required for PDF printing.' }
$pdfDirectory = Join-Path $reportRoot 'output\pdf'
New-Item -ItemType Directory -Path $pdfDirectory -ErrorAction SilentlyContinue | Out-Null
$pdfTarget = Join-Path $pdfDirectory '实验报告.pdf'
$temporaryRoot = Join-Path $reportRoot 'tmp'
New-Item -ItemType Directory -Path $temporaryRoot -ErrorAction SilentlyContinue | Out-Null
$profileName = 'report-print-' + [guid]::NewGuid().ToString('N')
$browserProfile = Join-Path $temporaryRoot $profileName
New-Item -ItemType Directory -Path $browserProfile | Out-Null
$htmlUri = ([uri]$htmlPath).AbsoluteUri
$arguments = @(
  '--headless=new', '--disable-gpu', '--disable-extensions', '--disable-background-networking',
  '--no-first-run', '--no-pdf-header-footer', '--run-all-compositor-stages-before-draw',
  '--virtual-time-budget=3000', ('--user-data-dir="' + $browserProfile + '"'),
  ('--print-to-pdf="' + $pdfTarget + '"'), ('"' + $htmlUri + '"')
)
try {
  $printingProcess = Start-Process -FilePath $browserExecutable -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
  if ($printingProcess.ExitCode -ne 0) { throw "PDF printing failed: exit $($printingProcess.ExitCode)." }
  if (-not (Test-Path -LiteralPath $pdfTarget) -or (Get-Item -LiteralPath $pdfTarget).Length -lt 1000) {
    throw 'Expected PDF was not produced.'
  }
  Get-Item -LiteralPath $pdfTarget | Select-Object Name,Length
} finally {
  $resolvedProfile = (Resolve-Path -LiteralPath $browserProfile).Path
  $resolvedTemporary = (Resolve-Path -LiteralPath $temporaryRoot).Path
  if (-not $resolvedProfile.StartsWith($resolvedTemporary + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
      (Split-Path -Leaf $resolvedProfile) -ne $profileName) { throw 'Unexpected browser-profile cleanup target.' }
  Remove-Item -LiteralPath $resolvedProfile -Recurse
}
