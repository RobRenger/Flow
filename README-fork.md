# Fork van Rob Renger: Flow met Google Cast

Deze fork (github.com/RobRenger/Flow) voegt casten naar Google Cast-speakers en speakergroepen toe aan de muziekspeler van Flow. Zonder Google Play Services, dus ook in de FOSS-variant. Upstream is github.com/Flow-Tube/Flow.

## Branches
- `main` volgt upstream één op één. Hier komen geen eigen commits.
- `chromecast` is `main` plus de eigen wijzigingen. Hiervan bouwt GitHub Actions de APK.

## Upstream binnenhalen

```bash
git fetch upstream
git checkout main
git merge --ff-only upstream/main
git push origin main
git checkout chromecast
git merge main
```

Gebruik merge, geen rebase. `chromecast` staat gepubliceerd op GitHub. Lukt `--ff-only` niet, dan staat er per ongeluk een eigen commit op `main`. Verplaats die naar `chromecast` en zet `main` terug op `upstream/main`.

## Waar conflicten te verwachten zijn
| Bestand | Eigen wijziging |
|---|---|
| `service/Media3MusicService.kt` | wissel naar de cast-speler en de service blijft actief tijdens casten |
| `player/dlna/StreamProxyServer.kt` | drie regels zodat de speaker de audiostream kan ophalen |
| `ui/components/musicplayer/full/PlayerTopBar.kt`, `FullMusicPlayerContent.kt` | cast-knop rechtsboven in de speler |
| `app/src/main/res/values/strings.xml` | teksten voor casten |

Alleen nieuw in de fork, dus zonder conflicten: `player/cast/`, `service/MusicCastHandover.kt`, `ui/components/musicplayer/CastSpeakerPicker.kt`, `.github/workflows/fork-build.yml` en de test `CastWireTest.kt`.

## Na elke merge controleren
1. De fork bouwt de FOSS-variant (`assembleFossDebug` in `fork-build.yml`). Daarin staat de updater uit (`UPDATER_ENABLED=false`), zodat de app zichzelf niet vervangt door de officiële versie. Bouw je ooit de `github`-variant, laat `data/update/UpdateRepository.kt` dan eerst naar RobRenger/Flow wijzen.
2. Push `chromecast`. De workflow bouwt de APK en draait de unittests. Het artefact `flow-foss-debug-arm64` staat bij de run op GitHub.
3. Kort testen op de telefoon: cast naar een speakergroep, volgend nummer, pauze, doorspoelen, volume, stoppen.
