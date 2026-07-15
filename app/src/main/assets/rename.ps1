# rename-example-folders.ps1
# Recursively renames all directories named "example" to "placeholder"
# Run from the root you want to search (e.g. your project's src folder)

param(
    [string]$RootPath = ".",
    [switch]$WhatIf   # dry run: pass -WhatIf to preview without renaming
)

$folders = Get-ChildItem -Path $RootPath -Directory -Recurse -Filter "example" |
    Sort-Object { $_.FullName.Length } -Descending  # deepest paths first

if ($folders.Count -eq 0) {
    Write-Host "No folders named 'example' found under $RootPath"
    exit
}

foreach ($folder in $folders) {
    $newPath = Join-Path $folder.Parent.FullName "placeholder"

    if (Test-Path $newPath) {
        Write-Warning "Skipping $($folder.FullName) - target 'placeholder' already exists"
        continue
    }

    if ($WhatIf) {
        Write-Host "[DRY RUN] Would rename: $($folder.FullName) -> $newPath"
    } else {
        try {
            Rename-Item -Path $folder.FullName -NewName "placeholder" -ErrorAction Stop
            Write-Host "Renamed: $($folder.FullName) -> $newPath"
        } catch {
            Write-Error "Failed to rename $($folder.FullName): $_"
        }
    }
}