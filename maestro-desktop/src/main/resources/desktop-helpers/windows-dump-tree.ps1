# Dump UI Automation hierarchy for a Windows process as JSON (stdout).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File windows-dump-tree.ps1 -Pid 1234
param(
  [Parameter(Mandatory = $true)]
  [int]$ProcessId
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

function Get-BoundsString($element) {
  try {
    $r = $element.Current.BoundingRectangle
    if ($null -eq $r -or $r.IsEmpty) { return "[0,0][0,0]" }
    $left = [int][Math]::Floor($r.X)
    $top = [int][Math]::Floor($r.Y)
    $right = [int][Math]::Ceiling($r.X + $r.Width)
    $bottom = [int][Math]::Ceiling($r.Y + $r.Height)
    return "[$left,$top][$right,$bottom]"
  } catch {
    return "[0,0][0,0]"
  }
}

function Convert-Element($element, [int]$depth) {
  if ($depth -gt 40 -or $null -eq $element) { return $null }
  try {
    $c = $element.Current
  } catch {
    return $null
  }

  $id = $c.AutomationId
  $name = $c.Name
  $role = [string]$c.ControlType.ProgrammaticName
  if ($role -like "ControlType.*") { $role = $role.Substring(12) }

  $clickable = $false
  try {
    [void]$element.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    $clickable = $true
  } catch {}

  $children = @()
  try {
    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $child = $walker.GetFirstChild($element)
    while ($null -ne $child) {
      $converted = Convert-Element $child ($depth + 1)
      if ($null -ne $converted) { $children += $converted }
      $child = $walker.GetNextSibling($child)
    }
  } catch {}

  return [ordered]@{
    id        = $(if ([string]::IsNullOrWhiteSpace($id)) { $null } else { $id })
    text      = $(if ([string]::IsNullOrWhiteSpace($name)) { "" } else { $name })
    role      = $role.ToLowerInvariant()
    bounds    = (Get-BoundsString $element)
    enabled   = [bool]$c.IsEnabled
    focused   = [bool]$c.HasKeyboardFocus
    selected  = $false
    clickable = $clickable
    children  = $children
  }
}

$rootEl = [System.Windows.Automation.AutomationElement]::RootElement
$condition = New-Object System.Windows.Automation.PropertyCondition(
  [System.Windows.Automation.AutomationElement]::ProcessIdProperty,
  $ProcessId
)
$app = $rootEl.FindFirst([System.Windows.Automation.TreeScope]::Children, $condition)
if ($null -eq $app) {
  $app = $rootEl.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)
}
if ($null -eq $app) {
  Write-Error "No UI Automation element found for PID $ProcessId"
  exit 2
}

$payload = [ordered]@{
  pid  = $ProcessId
  root = (Convert-Element $app 0)
}

$payload | ConvertTo-Json -Depth 80 -Compress
