$ErrorActionPreference = 'Stop'

# Framework UI source contracts only; these do not run an Activity or terminate a process.
function Assert-True($condition, $message) {
    if (-not $condition) { throw $message }
}

$root = Split-Path $PSScriptRoot -Parent
$activity = [System.IO.File]::ReadAllText((Join-Path $root 'src\main\java\com\gathin\deviceinfo\MainActivity.java'))
$resources = New-Object System.Xml.XmlDocument
$resources.Load((Join-Path $root 'src\main\res\values\strings.xml'))

Assert-True ($activity -match 'Button\s+exitApp\s*=\s*button\(R\.string\.exit_app,\s*R\.id\.exit_app_button\)') 'Main screen should create a dedicated exit-app button.'
Assert-True ($null -ne $resources.SelectSingleNode('/resources/item[@type="id" and @name="exit_app_button"]')) 'Exit button ID must be declared.'
$label = $resources.SelectSingleNode('/resources/string[@name="exit_app"]')
Assert-True ($null -ne $label -and -not [string]::IsNullOrWhiteSpace($label.InnerText)) 'Exit button needs a resource-backed label.'
$listener = [regex]::Match($activity, '(?s)exitApp\.setOnClickListener\(new View\.OnClickListener\(\)\s*\{\s*@Override\s+public void onClick\(View view\)\s*\{(?<body>.*?)\}\s*\}\);')
Assert-True $listener.Success 'Exit button needs its own listener.'
Assert-True ($listener.Groups['body'].Value -match '^\s*finishAffinity\(\);\s*$') 'Exit must close the app UI without killing a process, rebooting or scheduling an app restart.'
Assert-True ($activity -notmatch 'exitApp\.setEnabled\(|exitApp\.requestFocus\(') 'Exit must stay available without being the initial focus target.'
Write-Host 'PASS main exit: explicit normal UI close, no forced process termination'

Assert-True ($activity -match 'appActions\.addView\(installApk,\s*installSize\)') 'Install APK should belong to the shared app-actions row.'
Assert-True ($activity -match 'appActions\.addView\(exitApp,\s*new LinearLayout\.LayoutParams\(0,\s*ViewGroup\.LayoutParams\.WRAP_CONTENT,\s*1f\)\)') 'Exit should use equal weight in the app-actions row.'
Assert-True ($activity -match 'installSize\s*=\s*new LinearLayout\.LayoutParams\(0,\s*ViewGroup\.LayoutParams\.WRAP_CONTENT,\s*1f\)') 'Install APK should have the same weight as Exit.'
Assert-True ($activity -match 'root\.addView\(appActions,\s*appRow\)') 'Shared app-actions row should be attached to the main screen.'
Assert-True ($activity -notmatch 'root\.addView\(installApk,') 'Install APK should no longer occupy its own full-width row.'
Write-Host 'PASS main exit: paired equal-width Install/Exit layout'

foreach ($edge in @(
    'installApk.setNextFocusRightId(R.id.exit_app_button)',
    'exitApp.setNextFocusLeftId(R.id.install_apk_button)',
    'exitApp.setNextFocusDownId(R.id.floating_back_help)',
    'rebootButton.setNextFocusDownId(R.id.exit_app_button)'
)) { Assert-True ($activity.Contains($edge)) "Missing app-row D-pad link: $edge" }
Assert-True ($activity -match 'findViewById\(R\.id\.exit_app_button\)\.setNextFocusUpId\(rebootEnabled\s*\?\s*R\.id\.reboot_button\s*:\s*R\.id\.refresh_button\)') 'Exit-up focus should bypass a disabled reboot button.'
Write-Host 'PASS main exit: D-pad access and disabled-reboot fallback'
Write-Host 'Main navigation source checks: 3 passed.'

$service = [IO.File]::ReadAllText((Join-Path $root 'src/main/java/com/gathin/deviceinfo/RebootAccessibilityService.java'))
$overlay = [IO.File]::ReadAllText((Join-Path $root 'src/main/java/com/gathin/deviceinfo/FloatingBackButton.java'))
Assert-True ($activity -match 'Build\.VERSION\.SDK_INT >= 22') 'Floating back requires an API 22 gate.'
Assert-True ($service -match '(?s)onUnbind\(Intent intent\)\s*\{\s*removeFloatingBack\(\);') 'Unbinding must remove the floating button.'
Assert-True ($overlay -match '(?s)setOnClickListener.*performGlobalAction\(AccessibilityService\.GLOBAL_ACTION_BACK\)') 'Clicking the overlay should request system Back.'
Write-Host 'PASS floating back: API gate, service cleanup, and Back action'

Assert-True ($overlay -match '(?s)ACTION_MOVE.*updateViewLayout') 'Dragging should move the overlay.'
Assert-True ($overlay -match 'if \(dragging \|\| movedPastTouchSlop\(event\)\)\s*\{\s*cancelHome\(\);\s*return true;') 'Releasing a drag must not request Back.'
Write-Host 'PASS floating back: drag moves the window and cancels the release action'

Assert-True ($overlay -match '(?s)void remove\(\).*removeCallbacks\(dimButton\).*removeViewImmediate') 'Removing the overlay must stop its effects.'
Write-Host 'PASS floating back: idle fade cleanup'

Assert-True ($overlay -match '(?s)ACTION_UP:.*?if \(dragging \|\| movedPastTouchSlop\(event\)\).*?return true;.*?if \(homeReady\)\s*\{.*?view\.performLongClick\(\);') 'Only a stationary long press should request Home on release.'
Assert-True ($overlay -match '(?s)setOnLongClickListener.*performGlobalAction\(AccessibilityService\.GLOBAL_ACTION_HOME\)') 'Long press should request system Home.'
Write-Host 'PASS floating back: stationary long press goes Home on release'

Assert-True ($overlay -match '(?s)ACTION_DOWN:.*?postDelayed\(armHome, ViewConfiguration\.getLongPressTimeout\(\)\)') 'Hold should show a readiness hint before release.'
Assert-True ($overlay -match '(?s)ACTION_MOVE:.*?cancelHome\(\);') 'Dragging should cancel a pending Home action.'
Write-Host 'PASS floating back: long press readiness and drag cancellation'

Assert-True ($activity -notmatch 'floatingBackSwitch\.setText\(') 'The switch must have no clickable text label.'
Assert-True ($activity -match 'floatingBackHelpGroup\.setOnClickListener' -and $activity -notmatch 'floatingBackRow\.setOnClickListener|floatingBackLabel\.setOnClickListener|floatingBackHelp\.setOnClickListener') 'Only the shared label and help group should open help.'
Write-Host 'PASS floating back: independent switch and help targets'

Assert-True ($activity -notmatch 'floatingBackSwitch\.setText(On|Off)\(') 'The switch should keep its native on/off labels.'
Write-Host 'PASS floating back: native switch styling'

Assert-True ($activity.Contains('exitApp.setNextFocusForwardId(R.id.floating_back_help)')) 'Tab should enter the help group before the switch.'
Assert-True ($activity.Contains('floatingBackHelpGroup.setNextFocusForwardId(supportsFloatingBack')) 'Tab should skip an unavailable switch.'
Write-Host 'PASS floating back: explicit Tab order'

Assert-True ($activity.Contains('installApk.setNextFocusDownId(R.id.floating_back_help)')) 'D-pad should enter help even on unsupported Android.'
Assert-True ($activity.Contains('scroll.setNextFocusUpId(supportsFloatingBack ? R.id.floating_back_switch : R.id.floating_back_help)')) 'D-pad should return to help when the switch is unavailable.'
Write-Host 'PASS floating back: D-pad access on unsupported Android'
