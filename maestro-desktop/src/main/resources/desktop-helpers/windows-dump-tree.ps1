# The single JAR resource is also its own bounded native bootstrap worker.
param(
  [int]$ProcessId,
  [switch]$WorkerOnly,
  [switch]$MsaaOnly,
  [long]$ExpectedFlutterHandle,
  [long]$ExpectedStartTicks,
  [long]$ExpectedWindowHandle,
  [switch]$FunctionsOnly
)
$ErrorActionPreference = 'Stop'
# Java reads this process as UTF-8. Windows PowerShell otherwise writes the
# ANSI/OEM code page, so ä in an MSAA name becomes U+FFFD before Maestro matches.
$script:ProviderUtf8 = New-Object System.Text.UTF8Encoding $false
$OutputEncoding = $script:ProviderUtf8
try { [Console]::OutputEncoding = $script:ProviderUtf8 } catch {}

function Write-ProviderJson($Value) {
  $json = $Value | ConvertTo-Json -Depth 80 -Compress
  $bytes = $script:ProviderUtf8.GetBytes($json)
  $stdout = [Console]::OpenStandardOutput()
  $stdout.Write($bytes, 0, $bytes.Length)
  $stdout.Flush()
}

function Get-ProviderCanonicalHash([string]$Path) {
  $utf8=New-Object Text.UTF8Encoding($false,$true)
  $bytes=$utf8.GetBytes($utf8.GetString([IO.File]::ReadAllBytes($Path)).Replace("`r`n","`n"))
  $sha=[Security.Cryptography.SHA256]::Create()
  try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','').ToLowerInvariant() }
  finally { $sha.Dispose() }
}
function Get-ProviderSafeHash([string]$Path) {
  $item=Get-Item -LiteralPath $Path -Force
  if ($item -isnot [IO.FileInfo]) { throw 'UnsafeIdentityFile' }
  for ($ancestor=$item; $null -ne $ancestor; $ancestor=$ancestor.Parent) {
    if ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'UnsafeIdentityFile' }
    if ($ancestor -is [IO.FileInfo]) { $ancestor=$ancestor.Directory; if ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'UnsafeIdentityFile' } }
  }
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Test-ProviderIdentity($Process, [int]$TargetId, [string]$Exe, [long]$StartTicks, [int]$Session, [long]$WindowHandle=0) {
  try {
    $Process.Refresh()
    return -not $Process.HasExited -and $Process.Id -eq $TargetId -and [string]::Equals($Process.Path,$Exe,[StringComparison]::OrdinalIgnoreCase) -and $Process.StartTime.ToUniversalTime().Ticks -eq $StartTicks -and $Process.SessionId -eq $Session -and ($WindowHandle -eq 0 -or $Process.MainWindowHandle.ToInt64() -eq $WindowHandle)
  } catch { return $false }
}
function Get-ProviderEvidenceContext([string]$Exe, [string]$ScriptPath) {
  $appDir=Split-Path $Exe -Parent; $jobDir=Split-Path $appDir -Parent
  if ($jobDir -notmatch '^C:\\cache\\maestro-desktop-bridge\\jobs\\[1-9][0-9]*$' -or (Split-Path $appDir -Leaf) -ne 'app') { return $null }
  $manifestPath=Join-Path $jobDir 'release-identity.json'
  $null=Get-ProviderSafeHash $manifestPath
  $manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
  if ($manifest.schemaVersion -ne 1 -or $manifest.sourceSha -notmatch '^[a-f0-9]{40}$' -or $manifest.pipelinePin -notmatch '^v?[0-9]+\.[0-9]+\.[0-9]+$' -or $manifest.helperSha256 -ne (Get-ProviderCanonicalHash $ScriptPath) -or $manifest.copyHashesMatch -ne $true -or $manifest.copiedExeSha256 -notmatch '^[a-f0-9]{64}$') { throw 'ReleaseIdentityMismatch' }
  return @{ directory=$jobDir; sourceSha=$manifest.sourceSha; pipelinePin=$manifest.pipelinePin; helperSha256=$manifest.helperSha256; exeSha256=$manifest.copiedExeSha256 }
}
function Write-ProviderMetadata($Context, $Metadata) {
  if ($null -eq $Context) { return }
  # Context is derived only from the expected executable's fixed own job root.
  $path=Join-Path $Context.directory 'provider-identity.json'
  $temp=$path+'.new'
  $record=[ordered]@{}
  foreach ($name in @('schemaVersion','utc','processId','startUtc','sessionId','windowHandle','exePathMatches','exeSha256','sourceSha','pipelinePin','helperSha256','bootstrapOutcome','guardFailure')) { $record[$name]=$Metadata[$name] }
  $record.native=$null
  if ($null -ne $Metadata.native) {
    $record.native=[ordered]@{}
    foreach ($name in @('flutterChildren','flutterHandle','hResult','comObjectReturned','comObjectReleased')) { $record.native[$name]=$Metadata.native[$name] }
  }
  $record.msaa=$null
  if ($null -ne $Metadata.msaa) {
    $record.msaa=[ordered]@{}
    foreach ($name in @('attempted','outcome','childCount','rootState','nodeCount','maxDepth','filteredNodes','complete','rootReleased','treeUsed')) { $record.msaa[$name]=$Metadata.msaa[$name] }
  }
  $record | ConvertTo-Json -Depth 5 -Compress | Set-Content -LiteralPath $temp -Encoding UTF8
  Move-Item -LiteralPath $temp -Destination $path -Force
}
function Write-ProviderStartupTree($Process, [int]$TargetId, [string]$Exe, [long]$StartTicks, [int]$Session, $Context, $Metadata) {
  $Metadata.bootstrapOutcome='window-not-ready'
  Write-ProviderMetadata $Context $Metadata
  # The process may have exited while its EXE was hashed. Empty is readiness,
  # never a substitute for lost identity; recheck immediately before emitting it.
  if (-not (Test-ProviderIdentity $Process $TargetId $Exe $StartTicks $Session)) { throw 'IdentityMismatch' }
  Write-EmptyTree $TargetId
}
function Get-ProviderInteropSource {
  return @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class WindowsProviderBootstrap {
  public class Activation { public bool own, comObjectReturned, comObjectReleased; public int flutterChildren, hResult; public long flutterHandle; public string outcome="not-started"; }
  delegate bool EnumWindow(IntPtr window, IntPtr parameter);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
  [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr window, EnumWindow callback, IntPtr parameter);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassNameW(IntPtr window, StringBuilder buffer, int length);
  [DllImport("oleacc.dll")] static extern int AccessibleObjectFromWindow(IntPtr window, uint objectId, ref Guid iid, [MarshalAs(UnmanagedType.Interface)] out object accessible);
  [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
  public static bool IsOwn(IntPtr window, uint processId) { uint actual; return window != IntPtr.Zero && GetWindowThreadProcessId(window,out actual)!=0 && actual==processId; }
  public static string Decision(bool ownTop, bool ownChild, int count, int visited) {
    if (!ownTop) return "window-mismatch";
    if (visited>64) return "window-bound-exceeded";
    if (count==0) return "non-flutter";
    if (count!=1 || !ownChild) return "ambiguous-flutter-view";
    return "request";
  }
  public static Activation Activate(IntPtr top, uint processId) {
    var result=new Activation { hResult=unchecked((int)0x80004005) };
    if (!IsOwn(top,processId)) { result.outcome="window-mismatch"; return result; }
    IntPtr child=IntPtr.Zero; int visited=0;
    EnumChildWindows(top,delegate(IntPtr window,IntPtr unused) {
      if (++visited>64) return false;
      if (IsOwn(window,processId)) { var name=new StringBuilder(64); GetClassNameW(window,name,name.Capacity); if (name.ToString()=="FLUTTERVIEW") { result.flutterChildren++; child=window; } }
      return true;
    },IntPtr.Zero);
    result.own=IsOwn(top,processId);
    string decision=Decision(result.own,IsOwn(child,processId),result.flutterChildren,visited);
    if (decision!="request") { result.outcome=decision; if (decision!="non-flutter") result.own=false; return result; }
    result.flutterHandle=child.ToInt64();
    Guid iid=new Guid("618736e0-3c3d-11cf-810c-00aa00389b71"); object accessible=null;
    // oleacc consumes the marshaled WM_GETOBJECT result; never send it separately.
    try { result.hResult=AccessibleObjectFromWindow(child,0xfffffffc,ref iid,out accessible); result.comObjectReturned=accessible!=null; }
    finally { if (accessible!=null && Marshal.IsComObject(accessible)) { Marshal.ReleaseComObject(accessible); result.comObjectReleased=true; } }
    result.own=IsOwn(top,processId) && IsOwn(child,processId);
    result.outcome=result.own ? "requested" : "window-mismatch";
    return result;
  }
}
// This reader uses only MSAA read properties and AccessibleChildren. No values,
// descriptions, keyboard/focus requests, default-action invocation or writes.
public static class WindowsProviderMsaa {
  public interface IReadNode : IDisposable {
    bool Owned { get; } string Identity { get; } int ChildCount { get; }
    int State { get; } int Role { get; } string Name { get; }
    int[] Location { get; } bool HasDefaultAction { get; }
    IReadNode[] Children(int expected);
  }
  public class Tree {
    public string id=null, text="", role="unknown", bounds="[0,0][0,0]";
    public bool enabled, focused, selected, clickable;
    public Tree[] children=new Tree[0];
  }
  public class Snapshot {
    public string outcome="not-started"; public int childCount=-1, rootState=-1, nodeCount, maxDepth, filteredNodes;
    public bool own, complete, rootReleased; public Tree root;
  }
  sealed class ReadLimit : Exception { }
  sealed class ScopeMismatch : Exception { }
  sealed class ReleaseFailure : Exception { }
  static void DisposeNodes(IReadNode[] nodes) {
    bool failed=false;
    if(nodes!=null) foreach(IReadNode node in nodes) if(node!=null) { try { node.Dispose(); } catch { failed=true; } }
    if(failed) throw new ReleaseFailure();
  }
  static void ReleaseVariants(object[] values) {
    bool failed=false;
    foreach(object value in values) if(value!=null && Marshal.IsComObject(value)) { try { Marshal.ReleaseComObject(value); } catch { failed=true; } }
    if(failed) throw new ReleaseFailure();
  }
  sealed class Budget {
    public int nodes, textBytes, maxDepth, filteredNodes, rootState=-1;
    public System.Diagnostics.Stopwatch clock=System.Diagnostics.Stopwatch.StartNew();
    public System.Collections.Hashtable active=new System.Collections.Hashtable();
  }
  static string RoleName(int role) {
    switch(role) {
      case 1:return "titlebar"; case 3:return "scrollbar"; case 9:return "window";
      case 10:case 14:case 38:return "pane"; case 12:return "menuitem";
      case 13:return "tooltip"; case 15:return "document"; case 20:return "group";
      case 24:return "table"; case 25:case 26:return "headeritem";
      case 29:return "dataitem"; case 30:return "hyperlink"; case 33:return "list";
      case 34:return "listitem"; case 35:return "tree"; case 36:return "treeitem";
      case 37:return "tabitem"; case 40:return "image"; case 41:return "text";
      case 42:return "edit"; case 43:return "button"; case 44:return "checkbox";
      case 45:return "radiobutton"; case 46:return "combobox";
      case 48:return "progressbar"; case 51:return "slider"; default:return "unknown";
    }
  }
  static Tree Walk(IReadNode node,int depth,Budget budget,bool inheritedProtected) {
    if (node==null || !node.Owned) throw new ScopeMismatch();
    if (++budget.nodes>512 || depth>32 || budget.clock.ElapsedMilliseconds>2500) throw new ReadLimit();
    budget.maxDepth=Math.Max(budget.maxDepth,depth);
    string identity=node.Identity;
    if (budget.active.ContainsKey(identity)) throw new ReadLimit();
    budget.active.Add(identity,true);
    try {
      int count=node.ChildCount;
      if (count<0 || count>512 || count>512-budget.nodes) throw new ReadLimit();
      int state=node.State;
      if(depth==0) budget.rootState=state;
      // The Maestro tree has no independent MSAA visibility flag. Never expose
      // invisible/offscreen names as visible matches; a later real scroll may
      // make them visible on the next native snapshot. Do not read their names.
      if((state&0x18000)!=0) { budget.filteredNodes++; return null; }
      int role=node.Role;
      bool protectedText=inheritedProtected || (state&0x20000000)!=0;
      string name=protectedText ? "" : (node.Name ?? "");
      budget.textBytes+=Encoding.UTF8.GetByteCount(name);
      if (name.Length>4096 || budget.textBytes>65536) throw new ReadLimit();
      int[] box=node.Location;
      if (box==null || box.Length!=4 || box[2]<0 || box[3]<0) throw new ReadLimit();
      long right=(long)box[0]+box[2],bottom=(long)box[1]+box[3];
      if (right<int.MinValue || right>int.MaxValue || bottom<int.MinValue || bottom>int.MaxValue) throw new ReadLimit();
      var tree=new Tree { text=name,role=RoleName(role),bounds="["+box[0]+","+box[1]+"]["+right+","+bottom+"]",enabled=(state&1)==0,focused=(state&4)!=0,selected=(state&2)!=0,clickable=!protectedText && node.HasDefaultAction };
      IReadNode[] children=null;
      try {
        children=node.Children(count);
        if (children==null || children.Length!=count) throw new ReadLimit();
        var visible=new System.Collections.ArrayList();
        for(int i=0;i<count;i++) { Tree child=Walk(children[i],depth+1,budget,protectedText); if(child!=null) visible.Add(child); }
        tree.children=(Tree[])visible.ToArray(typeof(Tree));
      } finally { DisposeNodes(children); }
      return tree;
    } finally { budget.active.Remove(identity); }
  }
  // The same walker is fixture-tested with pure nodes; production supplies only
  // COM objects returned by this own FLUTTERVIEW's native MSAA provider.
  public static Snapshot Read(IReadNode root) {
    var result=new Snapshot(); var budget=new Budget();
    try {
      if(root==null || !root.Owned) throw new ScopeMismatch();
      result.own=true; result.childCount=root.ChildCount; budget.rootState=root.State;
      if(result.childCount==0) { result.outcome="empty"; result.complete=true; return result; }
      result.root=Walk(root,0,budget,false); result.complete=true; result.outcome=result.root==null ? "not-visible" : "complete";
    } catch(ScopeMismatch) { result.own=false; result.outcome="scope-mismatch"; result.root=null; }
      catch(ReadLimit) { result.outcome="truncated"; result.root=null; }
      catch(ReleaseFailure) { result.complete=false; result.outcome="release-failed"; result.root=null; }
      catch { result.outcome="read-failed"; result.root=null; }
    finally {
      result.nodeCount=budget.nodes; result.maxDepth=budget.maxDepth; result.filteredNodes=budget.filteredNodes; result.rootState=budget.rootState;
      if(root!=null) { try { root.Dispose();result.rootReleased=true; } catch { result.complete=false;result.root=null;result.outcome="release-failed"; } }
    }
    return result;
  }
  delegate bool EnumWindow(IntPtr window,IntPtr parameter);
  [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr window,EnumWindow callback,IntPtr parameter);
  [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetClassNameW(IntPtr window,StringBuilder buffer,int length);
  [DllImport("oleacc.dll")] static extern int AccessibleObjectFromWindow(IntPtr window,uint objectId,ref Guid iid,[MarshalAs(UnmanagedType.Interface)] out object accessible);
  [DllImport("oleacc.dll")] static extern int AccessibleChildren(IntPtr accessible,int start,int count,[Out,MarshalAs(UnmanagedType.LPArray,ArraySubType=UnmanagedType.Struct,SizeParamIndex=2)] object[] children,out int obtained);
  [DllImport("oleacc.dll")] static extern int WindowFromAccessibleObject(IntPtr accessible,out IntPtr window);
  static Guid AccessibleIid=new Guid("618736e0-3c3d-11cf-810c-00aa00389b71");
  static IntPtr AccessiblePointer(object accessible) {
    IntPtr unknown=Marshal.GetIUnknownForObject(accessible),result=IntPtr.Zero;
    try { Guid iid=AccessibleIid; object[] query={unknown,iid,IntPtr.Zero}; int hr=(int)typeof(Marshal).GetMethod("QueryInterface").Invoke(null,query); result=(IntPtr)query[2]; if(hr!=0 || result==IntPtr.Zero) { if(result!=IntPtr.Zero) Marshal.Release(result); throw new ScopeMismatch(); } return result; }
    finally { Marshal.Release(unknown); }
  }
  sealed class NativeNode : IReadNode {
    readonly object accessible; readonly int childId; readonly IntPtr flutter; readonly uint pid;
    readonly bool ownsReference; bool released;
    public NativeNode(object accessible,int childId,IntPtr flutter,uint pid,bool ownsReference) { this.accessible=accessible;this.childId=childId;this.flutter=flutter;this.pid=pid;this.ownsReference=ownsReference; }
    object Property(string name,params object[] args) { return accessible.GetType().InvokeMember(name,System.Reflection.BindingFlags.GetProperty,null,accessible,args,System.Globalization.CultureInfo.InvariantCulture); }
    public bool Owned { get {
      if(!WindowsProviderBootstrap.IsOwn(flutter,pid)) return false;
      IntPtr pointer=AccessiblePointer(accessible);
      try { IntPtr window; return WindowFromAccessibleObject(pointer,out window)==0 && window==flutter && WindowsProviderBootstrap.IsOwn(window,pid); }
      finally { Marshal.Release(pointer); }
    } }
    public string Identity { get { IntPtr p=Marshal.GetIUnknownForObject(accessible); try { return p.ToInt64().ToString(System.Globalization.CultureInfo.InvariantCulture)+":"+childId; } finally { Marshal.Release(p); } } }
    public int ChildCount { get { return childId==0 ? Convert.ToInt32(Property("accChildCount")) : 0; } }
    public int State { get { return Convert.ToInt32(Property("accState",childId)); } }
    public int Role { get { return Convert.ToInt32(Property("accRole",childId)); } }
    public string Name { get { return (string)Property("accName",childId); } }
    public bool HasDefaultAction { get { try { return !String.IsNullOrEmpty((string)Property("accDefaultAction",childId)); } catch { return false; } } }
    public int[] Location { get {
      object[] args={0,0,0,0,childId}; var modifier=new System.Reflection.ParameterModifier(5);
      for(int i=0;i<4;i++) modifier[i]=true;
      accessible.GetType().InvokeMember("accLocation",System.Reflection.BindingFlags.InvokeMethod,null,accessible,args,new System.Reflection.ParameterModifier[]{modifier},System.Globalization.CultureInfo.InvariantCulture,null);
      return new int[]{Convert.ToInt32(args[0]),Convert.ToInt32(args[1]),Convert.ToInt32(args[2]),Convert.ToInt32(args[3])};
    } }
    public IReadNode[] Children(int expected) {
      if(expected==0) return new IReadNode[0];
      object[] variants=new object[expected]; var nodes=new IReadNode[expected];
      IntPtr pointer=AccessiblePointer(accessible);
      try {
        int got; int hr=AccessibleChildren(pointer,0,expected,variants,out got);
        if(hr!=0 || got!=expected) throw new ReadLimit();
        for(int i=0;i<expected;i++) {
          object value=variants[i];
          if(value is int) { int id=(int)value; if(id<=0) throw new ReadLimit(); nodes[i]=new NativeNode(accessible,id,flutter,pid,false); }
          else if(value!=null && Marshal.IsComObject(value)) { nodes[i]=new NativeNode(value,0,flutter,pid,true); variants[i]=null; }
          else throw new ReadLimit();
        }
        return nodes;
      } catch { DisposeNodes(nodes); throw; }
      finally { try { Marshal.Release(pointer); } finally { ReleaseVariants(variants); } }
    }
    public void Dispose() { if(!released) { released=true; if(ownsReference && Marshal.IsComObject(accessible)) Marshal.ReleaseComObject(accessible); } }
  }
  static bool UniqueFlutter(IntPtr top,uint pid,IntPtr expectedFlutter) {
    if(!WindowsProviderBootstrap.IsOwn(top,pid) || !WindowsProviderBootstrap.IsOwn(expectedFlutter,pid)) return false;
    int visited=0,count=0;IntPtr found=IntPtr.Zero;
    EnumChildWindows(top,delegate(IntPtr window,IntPtr unused) {
      if(++visited>64) return false;
      if(WindowsProviderBootstrap.IsOwn(window,pid)) { var name=new StringBuilder(64);GetClassNameW(window,name,name.Capacity);if(name.ToString()=="FLUTTERVIEW") { count++;found=window; } }
      return true;
    },IntPtr.Zero);
    return visited<=64 && count==1 && found==expectedFlutter;
  }
  public static Snapshot Native(IntPtr top,uint pid,IntPtr expectedFlutter) {
    if(!UniqueFlutter(top,pid,expectedFlutter)) return new Snapshot {outcome="scope-mismatch"};
    IntPtr found=expectedFlutter;
    object accessible=null;NativeNode root=null;
    try {
      Guid iid=AccessibleIid;
      int hr=AccessibleObjectFromWindow(found,0xfffffffc,ref iid,out accessible);
      if(hr!=0 || accessible==null || !Marshal.IsComObject(accessible)) return new Snapshot {outcome="read-failed"};
      root=new NativeNode(accessible,0,found,pid,true); accessible=null;
      Snapshot result=Read(root); root=null;
      if(!UniqueFlutter(top,pid,expectedFlutter)) { result.own=false;result.root=null;result.outcome="scope-mismatch"; }
      return result;
    } catch { return new Snapshot {outcome="read-failed"}; }
    finally { if(root!=null) root.Dispose(); if(accessible!=null && Marshal.IsComObject(accessible)) Marshal.ReleaseComObject(accessible); }
  }
}
'@
}
function Complete-ProviderWorker($Worker, $Stdout, $Stderr, [int]$TimeoutMs) {
  try {
    if (-not $Worker.WaitForExit([Math]::Max(1,$TimeoutMs-400))) { throw 'ProviderWorkerTimeout' }
    if (-not $Stdout.Wait(100) -or -not $Stderr.Wait(100)) { throw 'ProviderWorkerPipeTimeout' }
    if ($Worker.ExitCode -ne 0) { throw 'ProviderWorkerFailed' }
    if ([Text.Encoding]::UTF8.GetByteCount([string]$Stdout.Result) -gt 262144) { throw 'ProviderWorkerOutputLimit' }
    return ($Stdout.Result | ConvertFrom-Json)
  } finally {
    # The caller created and retained this exact worker. Never stop the app.
    if (-not $Worker.HasExited) { $Worker.Kill(); $null=$Worker.WaitForExit(100) }
    $Worker.Dispose()
  }
}
function Invoke-ProviderWorker([string]$ScriptPath, [int]$TargetId, [long]$StartTicks, [long]$WindowHandle, [switch]$ReadMsaa, [long]$FlutterHandle) {
  $clock=[Diagnostics.Stopwatch]::StartNew()
  $start=New-Object Diagnostics.ProcessStartInfo
  $start.FileName=Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
  $start.Arguments='-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$ScriptPath+'" -WorkerOnly -ProcessId '+$TargetId+' -ExpectedStartTicks '+$StartTicks+' -ExpectedWindowHandle '+$WindowHandle
  if ($ReadMsaa) { $start.Arguments += ' -MsaaOnly -ExpectedFlutterHandle '+$FlutterHandle }
  $start.UseShellExecute=$false; $start.CreateNoWindow=$true; $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
  $start.StandardOutputEncoding=$script:ProviderUtf8; $start.StandardErrorEncoding=$script:ProviderUtf8
  $worker=[Diagnostics.Process]::Start($start); $retainedWorkerHandle=$worker.Handle
  $stdout=$worker.StandardOutput.ReadToEndAsync(); $stderr=$worker.StandardError.ReadToEndAsync()
  return Complete-ProviderWorker $worker $stdout $stderr ([Math]::Max(1,4000-[int]$clock.ElapsedMilliseconds))
}
function Get-BoundsString($element) {
  try { $r=$element.Current.BoundingRectangle; if ($null -eq $r -or $r.IsEmpty) { return '[0,0][0,0]' }; return '['+[int][Math]::Floor($r.X)+','+[int][Math]::Floor($r.Y)+']['+[int][Math]::Ceiling($r.X+$r.Width)+','+[int][Math]::Ceiling($r.Y+$r.Height)+']' } catch { return '[0,0][0,0]' }
}
function Convert-Element($element, [int]$depth) {
  if ($depth -gt 40 -or $null -eq $element) { return $null }
  try { $c=$element.Current } catch { return $null }
  $id=$c.AutomationId; $name=$c.Name; $role=[string]$c.ControlType.ProgrammaticName
  if ($role -like 'ControlType.*') { $role=$role.Substring(12) }
  $clickable=$false
  try { [void]$element.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern); $clickable=$true } catch {}
  $children=@()
  try { $walker=[System.Windows.Automation.TreeWalker]::ControlViewWalker; $child=$walker.GetFirstChild($element); while ($null -ne $child) { $converted=Convert-Element $child ($depth+1); if ($null -ne $converted) { $children+=$converted }; $child=$walker.GetNextSibling($child) } } catch {}
  return [ordered]@{ id=$(if ([string]::IsNullOrWhiteSpace($id)) { $null } else { $id }); text=$(if ([string]::IsNullOrWhiteSpace($name)) { '' } else { $name }); role=$role.ToLowerInvariant(); bounds=(Get-BoundsString $element); enabled=[bool]$c.IsEnabled; focused=[bool]$c.HasKeyboardFocus; selected=$false; clickable=$clickable; children=$children }
}
function Test-ProviderEmptyFlutterTree($Root) {
  if ($null -eq $Root -or $Root.role -ne 'window' -or @($Root.children).Count -ne 1) { return $false }
  $child=@($Root.children)[0]
  return $null -ne $child -and $child.role -eq 'pane' -and $child.text -ceq 'FLUTTERVIEW' -and @($child.children).Count -eq 0
}
function Test-ProviderMsaaResult($Result) {
  return $null -ne $Result -and $Result.outcome -ceq 'complete' -and $Result.own -eq $true -and $Result.complete -eq $true -and $Result.rootReleased -eq $true -and $Result.childCount -gt 0 -and $Result.nodeCount -gt 1 -and $Result.nodeCount -le 512 -and $Result.maxDepth -le 32 -and $null -ne $Result.root -and @($Result.root.children).Count -gt 0 -and @($Result.root.children).Count -le $Result.childCount
}
function Write-EmptyTree([int]$TargetId) { Write-Output ('{"pid":'+$TargetId+',"root":{"text":"","role":"window","bounds":"[0,0][0,0]","enabled":true,"focused":false,"selected":false,"clickable":false,"children":[]}}') }
function Show-BridgeToolbox([int]$TargetId, [string]$Exe, [long]$WindowHandle) {
  # Only the CI bridge copy. A covered or leaked toolbox window makes the
  # later Robot tap hit whatever is painted at the same screen point.
  $root = 'C:\cache\maestro-desktop-bridge\jobs\'
  if (-not $Exe.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { return }
  Get-CimInstance Win32_Process -Filter "Name = 'mainpath_toolbox.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.ProcessId -eq $TargetId) { return }
    $path = [string]$_.ExecutablePath
    if (-not $path.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { return }
    $otherJob = Split-Path (Split-Path $path -Parent) -Parent
    $finished = Test-Path -LiteralPath (Join-Path $otherJob 'exitcode.txt')
    $ageMin = 0
    try { $ageMin = ((Get-Date) - [Management.ManagementDateTimeConverter]::ToDateTime($_.CreationDate)).TotalMinutes } catch {}
    if ($finished -or $ageMin -gt 20) { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  }
  if (-not ('WindowsProviderRaise' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class WindowsProviderRaise {
  [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hWnd, int cmd);
  [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
  public static void Raise(IntPtr window) {
    ShowWindow(window, 9);
    IntPtr fg = GetForegroundWindow();
    uint fgPid; uint fgThread = GetWindowThreadProcessId(fg, out fgPid);
    uint me = GetCurrentThreadId();
    bool attached = fgThread != 0 && fgThread != me && AttachThreadInput(me, fgThread, true);
    SetForegroundWindow(window);
    SetWindowPos(window, new IntPtr(-1), 0, 0, 0, 0, 0x43);
    if (attached) AttachThreadInput(me, fgThread, false);
  }
}
'@ | Out-Null
  }
  [WindowsProviderRaise]::Raise([IntPtr]$WindowHandle)
}
if ($FunctionsOnly) { return }
$process=$null; $context=$null
$metadata=[ordered]@{ schemaVersion=1; utc=[DateTime]::UtcNow.ToString('o'); processId=$ProcessId; startUtc=$null; sessionId=$null; windowHandle=$null; exePathMatches=$false; exeSha256=$null; sourceSha=$null; pipelinePin=$null; helperSha256=$null; bootstrapOutcome='not-started'; guardFailure=$null; native=$null; msaa=$null }
try {
  if ($ProcessId -le 0 -or [string]::IsNullOrWhiteSpace($env:MAESTRO_DESKTOP_APP)) { throw 'MissingExpectedIdentity' }
  $exe=[IO.Path]::GetFullPath($env:MAESTRO_DESKTOP_APP)
  $context=Get-ProviderEvidenceContext $exe $PSCommandPath
  if ($context) { $metadata.sourceSha=$context.sourceSha; $metadata.pipelinePin=$context.pipelinePin; $metadata.helperSha256=$context.helperSha256 }
  $process=[Diagnostics.Process]::GetProcessById($ProcessId); $retainedAppHandle=$process.Handle
  Add-Type -TypeDefinition (Get-ProviderInteropSource) | Out-Null
  $session=[Diagnostics.Process]::GetCurrentProcess().SessionId
  if ($session -le 0 -or $session -ne [WindowsProviderBootstrap]::WTSGetActiveConsoleSessionId()) { throw 'SessionMismatch' }
  $startTicks=$process.StartTime.ToUniversalTime().Ticks
  $windowHandle=$process.MainWindowHandle.ToInt64()
  if ($WorkerOnly) { $startTicks=$ExpectedStartTicks; $windowHandle=$ExpectedWindowHandle }
  if (-not (Test-ProviderIdentity $process $ProcessId $exe $startTicks $session $windowHandle)) { throw 'IdentityMismatch' }
  $metadata.exePathMatches=$true; $metadata.startUtc=$process.StartTime.ToUniversalTime().ToString('o'); $metadata.sessionId=$session; $metadata.windowHandle=$windowHandle
  $exeHash=Get-ProviderSafeHash $exe; $metadata.exeSha256=$exeHash
  if ($context -and $exeHash -ne $context.exeSha256) { throw 'ReleaseIdentityMismatch' }
  if ($windowHandle -eq 0) { if ($WorkerOnly) { throw 'WindowMismatch' }; Write-ProviderStartupTree $process $ProcessId $exe $startTicks $session $context $metadata; exit 0 }
  if (-not [WindowsProviderBootstrap]::IsOwn([IntPtr]$windowHandle,[uint32]$ProcessId)) { throw 'WindowMismatch' }
  if ($WorkerOnly) {
    if ($MsaaOnly) {
      $snapshot=[WindowsProviderMsaa]::Native([IntPtr]$windowHandle,[uint32]$ProcessId,[IntPtr]$ExpectedFlutterHandle)
      if (-not (Test-ProviderIdentity $process $ProcessId $exe $startTicks $session $windowHandle) -or (Get-ProviderSafeHash $exe) -ne $exeHash) { throw 'IdentityMismatch' }
      Write-ProviderJson $snapshot
      exit 0
    }
    $native=[WindowsProviderBootstrap]::Activate([IntPtr]$windowHandle,[uint32]$ProcessId)
    if (-not $native.own -or $native.outcome -notin @('requested','non-flutter') -or -not (Test-ProviderIdentity $process $ProcessId $exe $startTicks $session $windowHandle) -or (Get-ProviderSafeHash $exe) -ne $exeHash) { throw 'IdentityMismatch' }
    Write-ProviderJson $native
    exit 0
  }
  $native=Invoke-ProviderWorker $PSCommandPath $ProcessId $startTicks $windowHandle
  $metadata.bootstrapOutcome=$native.outcome
  $metadata.native=@{ flutterChildren=[int]$native.flutterChildren; flutterHandle=[long]$native.flutterHandle; hResult=[int]$native.hResult; comObjectReturned=[bool]$native.comObjectReturned; comObjectReleased=[bool]$native.comObjectReleased }
  if (-not $native.own -or $native.outcome -notin @('requested','non-flutter') -or -not (Test-ProviderIdentity $process $ProcessId $exe $startTicks $session $windowHandle)) { throw 'IdentityMismatch' }
  Add-Type -AssemblyName UIAutomationClient
  Add-Type -AssemblyName UIAutomationTypes
  # Bind the same own HWND, not another process or a second same-PID window.
  $app=[System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$windowHandle)
  if ($null -eq $app -or $app.Current.ProcessId -ne $ProcessId -or $app.Current.NativeWindowHandle -ne $windowHandle) { throw 'WindowMismatch' }
  $root=Convert-Element $app 0
  # UIA remains authoritative whenever it contains real content or is non-Flutter.
  # Only read a genuine own native MSAA tree for this exact empty Flutter shape.
  if ($native.outcome -eq 'requested' -and (Test-ProviderEmptyFlutterTree $root)) {
    $child=[System.Windows.Automation.TreeWalker]::ControlViewWalker.GetFirstChild($app)
    if ($null -eq $child -or $child.Current.ProcessId -ne $ProcessId -or $child.Current.NativeWindowHandle -ne $native.flutterHandle -or $child.Current.ClassName -cne 'FLUTTERVIEW') { throw 'WindowMismatch' }
    $metadata.msaa=@{ attempted=$true; outcome='not-started'; childCount=-1; rootState=-1; nodeCount=0; maxDepth=0; filteredNodes=0; complete=$false; rootReleased=$false; treeUsed=$false }
    try {
      $snapshot=Invoke-ProviderWorker $PSCommandPath $ProcessId $startTicks $windowHandle -ReadMsaa -FlutterHandle $native.flutterHandle
      if ($snapshot.outcome -notin @('complete','empty','not-visible','scope-mismatch','truncated','read-failed','release-failed')) { throw 'ProviderWorkerFailed' }
      if ($snapshot.outcome -ceq 'scope-mismatch') { throw 'WindowMismatch' }
      foreach ($name in @('outcome','childCount','rootState','nodeCount','maxDepth','filteredNodes','complete','rootReleased')) { $metadata.msaa[$name]=$snapshot.$name }
      if (Test-ProviderMsaaResult $snapshot) { $root.children=@($snapshot.root); $metadata.msaa.treeUsed=$true }
    } catch {
      if ($_.Exception.Message -in @('WindowMismatch','IdentityMismatch','ReleaseIdentityMismatch')) { throw }
      $metadata.msaa.outcome='worker-failed'
      if ($_.Exception.Message -eq 'ProviderWorkerTimeout' -or $_.Exception.Message -eq 'ProviderWorkerPipeTimeout') { $metadata.msaa.outcome='worker-timeout' }
    }
  }
  if ($null -ne $metadata.msaa -and -not [WindowsProviderBootstrap]::IsOwn([IntPtr]$native.flutterHandle,[uint32]$ProcessId)) { throw 'WindowMismatch' }
  if (-not (Test-ProviderIdentity $process $ProcessId $exe $startTicks $session $windowHandle) -or -not [WindowsProviderBootstrap]::IsOwn([IntPtr]$windowHandle,[uint32]$ProcessId) -or (Get-ProviderSafeHash $exe) -ne $exeHash) { throw 'IdentityMismatch' }
  Show-BridgeToolbox $ProcessId $exe $windowHandle
  Write-ProviderMetadata $context $metadata
  Write-ProviderJson @{ pid=$ProcessId; root=$root }
} catch {
  $reason='ProviderReadFailed'
  if ($_.Exception.Message -in @('MissingExpectedIdentity','UnsafeIdentityFile','ReleaseIdentityMismatch','SessionMismatch','IdentityMismatch','WindowMismatch','ProviderWorkerTimeout','ProviderWorkerPipeTimeout','ProviderWorkerFailed')) { $reason=$_.Exception.Message }
  $metadata.guardFailure=$reason; $metadata.bootstrapOutcome='aborted'
  if (-not $WorkerOnly) { Write-ProviderMetadata $context $metadata }
  Write-Output ('Windows provider guard failed: '+$reason)
  exit 2
} finally { if ($process) { $process.Dispose() } }
