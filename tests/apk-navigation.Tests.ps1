$ErrorActionPreference = 'Stop'

# Source-contract guards for framework UI wiring, not Android instrumentation/UI tests.
function Assert-True($condition, $message) {
    if (-not $condition) { throw $message }
}

$root = Split-Path $PSScriptRoot -Parent
$activity = [System.IO.File]::ReadAllText((Join-Path $root 'src\main\java\com\gathin\deviceinfo\ApkInstallActivity.java'))
$resources = New-Object System.Xml.XmlDocument
$resources.Load((Join-Path $root 'src\main\res\values\strings.xml'))

Assert-True ($activity -match 'Button\s+backButton\s*=\s*navigationButton\(R\.string\.apk_return_home,\s*R\.id\.apk_return_home\)') 'APK home return should use the lightweight navigation-button style.'
Assert-True ($activity -match 'header\.addView\(backButton,') 'Return button should be in the page header, outside the conditional file browser.'
Assert-True ($activity.IndexOf('header.addView(backButton,') -lt $activity.IndexOf('header.addView(title,')) 'Return button should precede the title.'
Assert-True ($activity.IndexOf('root.addView(header,') -ge 0 -and $activity.IndexOf('root.addView(header,') -lt $activity.IndexOf('root.addView(choices,')) 'Header should be attached above file-selection controls.'
Assert-True ($null -ne $resources.SelectSingleNode('/resources/item[@type="id" and @name="apk_return_home"]')) 'Return button ID must be declared.'
foreach ($name in @('apk_return_home', 'apk_return_home_description')) {
    $text = $resources.SelectSingleNode("/resources/string[@name='$name']")
    Assert-True ($null -ne $text -and -not [string]::IsNullOrWhiteSpace($text.InnerText)) "Missing return-button string: $name"
}
Assert-True ($activity -match 'backButton\.setContentDescription\(getString\(R\.string\.apk_return_home_description\)\)') 'Return button should describe its main-screen destination for accessibility.'
Write-Host 'PASS APK return button: visible header placement and resource-backed label/description'

$listener = [regex]::Match($activity, '(?s)backButton\.setOnClickListener\(new View\.OnClickListener\(\)\s*\{\s*@Override\s+public void onClick\(View view\)\s*\{(?<body>.*?)\}\s*\}\);')
Assert-True $listener.Success 'Return button should have its own click listener.'
Assert-True ($listener.Groups['body'].Value -match '^\s*finish\(\);\s*$') 'Return must unconditionally finish this Activity, not navigate folders, gate on preparation, launch an installer or create another main Activity.'
Assert-True ($activity -notmatch 'backButton\.set(?:Enabled|Visibility)\(') 'Return button should stay available independently of the installation state.'
Write-Host 'PASS APK return button: direct Activity finish, independent of directory/preparation state'

Assert-True ($activity -match 'backButton\.setNextFocusDownId\(R\.id\.apk_picker\)') 'Return button should reach the picker with D-pad down.'
foreach ($button in @('pickButton', 'browseButton')) {
    Assert-True ($activity -match ($button + '\.setNextFocusUpId\(R\.id\.apk_return_home\)')) "$button should reach return with D-pad up."
}
Assert-True ($activity -match '(?s)upButton\.setOnClickListener\(.*?onClick\(View view\)\s*\{\s*parent\(\);\s*\}') 'Directory-up navigation should remain separate from returning to the main screen.'
Write-Host 'PASS APK return button: D-pad access and separate directory-up action'

Assert-True ($activity -match 'upButton\s*=\s*navigationButton\(R\.string\.apk_parent,\s*R\.id\.apk_up\)') 'Directory-up should share the same lightweight navigation style.'
$helper = [regex]::Match($activity, '(?ms)^    private Button navigationButton\(int label, int id\)\s*\{(?<body>.*?)^    \}')
Assert-True $helper.Success 'A shared navigation-button helper should exist.'
Assert-True ($helper.Groups['body'].Value -match 'button\(label, id\)' -and $activity -match 'setMinHeight\(dp\(48\)\)') 'Navigation buttons must retain the common 48dp minimum touch height.'
Assert-True ($helper.Groups['body'].Value -match 'setMinWidth\(dp\(48\)\)' -and $helper.Groups['body'].Value -match 'setMinimumWidth\(dp\(48\)\)') 'Navigation buttons should use a compact but accessible minimum width.'
Assert-True ($helper.Groups['body'].Value -match 'R\.drawable\.navigation_button' -and $helper.Groups['body'].Value -match 'R\.color\.navigation_button_text') 'Navigation buttons should use shared state-list background and text colors.'
Write-Host 'PASS APK navigation: shared lightweight style with 48dp minimum targets'

Assert-True ($activity.IndexOf('navigation.addView(path,') -ge 0 -and $activity.IndexOf('navigation.addView(path,') -lt $activity.IndexOf('navigation.addView(upButton,')) 'Directory path should precede the right-aligned up control.'
Assert-True ($activity -match 'boolean hasParent\s*=\s*state\.showBrowser\s*&&\s*state\.directory\s*!=\s*null') 'Directory-up visibility should reflect an actual browsed directory.'
Assert-True ($activity -match 'upButton\.setVisibility\(hasParent\s*\?\s*View\.VISIBLE\s*:\s*View\.GONE\)') 'Storage-location list should not show a redundant up control.'
Assert-True ($activity -match 'files\.setNextFocusUpId\(upAvailable\s*\?\s*R\.id\.apk_up\s*:\s*R\.id\.apk_browser\)') 'List-up focus must not target a hidden or disabled up control.'
Assert-True ($activity -match 'upButton\.setNextFocusDownId\(R\.id\.apk_files\)' -and $activity -match 'upButton\.setNextFocusUpId\(R\.id\.apk_browser\)') 'The right-side directory control should connect to source selection and files.'
Assert-True ($activity -match 'upButton\.setContentDescription\(getString\(R\.string\.apk_parent_description\)\)') 'Directory navigation needs a non-symbol-only accessibility label.'
Write-Host 'PASS APK navigation: path-first directory toolbar and focus fallback'

$drawablePath = Join-Path $root 'src\main\res\drawable\navigation_button.xml'
$colorPath = Join-Path $root 'src\main\res\color\navigation_button_text.xml'
Assert-True (Test-Path -LiteralPath $drawablePath) 'Lightweight navigation drawable should exist.'
Assert-True (Test-Path -LiteralPath $colorPath) 'Navigation text state list should exist.'
$drawable = New-Object System.Xml.XmlDocument
$drawable.Load($drawablePath)
$colors = New-Object System.Xml.XmlDocument
$colors.Load($colorPath)
foreach ($xml in @($drawable, $colors)) {
    $ns = New-Object System.Xml.XmlNamespaceManager($xml.NameTable)
    $ns.AddNamespace('android', 'http://schemas.android.com/apk/res/android')
    foreach ($state in @('state_enabled', 'state_pressed', 'state_focused')) {
        Assert-True ($null -ne $xml.SelectSingleNode("/selector/item[@android:$state]", $ns)) "Navigation resource needs $state styling."
    }
}
$ns = New-Object System.Xml.XmlNamespaceManager($drawable.NameTable)
$ns.AddNamespace('android', 'http://schemas.android.com/apk/res/android')
$background = $drawable.SelectSingleNode('/selector/item[not(@*)]/shape/solid', $ns)
Assert-True ($null -ne $background -and $background.GetAttribute('color', 'http://schemas.android.com/apk/res/android') -eq '#00000000') 'Idle navigation must be transparent, not another filled primary action.'
Assert-True ($null -ne $drawable.SelectSingleNode('/selector/item[@android:state_focused="true"]/shape/stroke', $ns)) 'D-pad focus needs a visible outline.'
Write-Host 'PASS APK navigation: transparent idle state and visible pressed/focus states'
Write-Host 'APK navigation source checks: 6 passed.'
