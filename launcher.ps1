<#
.SYNOPSIS
    Lance le serveur Paper local du workspace RPGQuest (run/), sans copier
    manuellement de jar. Alternative en ligne de commande à la tâche VS Code
    "Gradle: runServer (Paper local, run/)" — voir docs/LOCAL_SERVER.md.

.DESCRIPTION
    Compile RPGQuest et démarre un serveur Paper local via le plugin Gradle
    xyz.jpenilla.run-paper (gradlew runServer). Au tout premier lancement,
    Paper s'arrête immédiatement en générant run/eula.txt : ce script le
    détecte et affiche l'instruction à suivre plutôt que d'accepter l'EULA
    Mojang à la place de l'utilisateur (décision qui doit rester manuelle).

.EXAMPLE
    .\launcher.ps1
#>

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

if (-not (Test-Path (Join-Path $PSScriptRoot "build.gradle.kts"))) {
    Write-Error "build.gradle.kts introuvable : ce script doit être lancé depuis la racine du dépôt RPGQuest."
    exit 1
}

$gradlew = Join-Path $PSScriptRoot "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Error "gradlew.bat introuvable."
    exit 1
}

$eulaFile = Join-Path $PSScriptRoot "run\eula.txt"
if (Test-Path $eulaFile) {
    $eulaContent = Get-Content $eulaFile -Raw
    if ($eulaContent -notmatch "eula\s*=\s*true") {
        Write-Warning "EULA Mojang non acceptée dans run\eula.txt."
        Write-Host "Ouvrez ce fichier, remplacez eula=false par eula=true (usage de test local uniquement)," -ForegroundColor Yellow
        Write-Host "puis relancez ce script." -ForegroundColor Yellow
        exit 1
    }
}

Write-Host "=== RPGQuest : démarrage du serveur Paper local (run/) ===" -ForegroundColor Cyan
Write-Host "Arrêt propre : tapez 'stop' dans la console du serveur, ou Ctrl+C." -ForegroundColor DarkGray
Write-Host ""

& $gradlew runServer --console=plain
exit $LASTEXITCODE
