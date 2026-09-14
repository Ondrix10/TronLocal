# TronLocal – Android

Lokální 2–4 hráčová retro hra pro 1 telefon.

## Nejjednodušší sestavení
1. Otevři složku `TronLocal` v Android Studiu.
2. Nech Gradle synchronizovat projekt.
3. Nainstaluj Android SDK Platform 35 a Build Tools 35.0.0, pokud je Android Studio nabídne.
4. Zvol telefon nebo emulátor.
5. Spusť `app` přes Run. Pro APK zvol **Build → Build APK(s)**.

Výsledné debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## Alternativa bez ručního buildu na PC
Projekt obsahuje GitHub Actions workflow `.github/workflows/build.yml`, který po nahrání do GitHubu sestaví APK a uloží ho jako artifact.

## Herní pravidla
- 2–4 hráči na jednom displeji.
- Každý hráč má vlastní dotykovou zónu; levá/pravá strana zóny zatáčí.
- Stopu tvoří bitmapa s vypnutým antialiasingem.
- Kolize se zjišťuje před hlavou, aby se právě vykreslený pixel nestal okamžitě sebe-kolizí.
- Stopy mají krátké náhodné mezery, kterými lze projet.
- Pořadí KO dává body 0…N-1 a poslední přeživší dostává nejvyšší počet bodů.
