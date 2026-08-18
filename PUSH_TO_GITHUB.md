# Repo submission (Google Form opens 10 PM)

Do this NOW, not at 9:58. Commit history is your proof of authorship.

## 1. Init and commit in stages (30 seconds)

```bash
cd MoboSafeDashcam
git init
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle app/build.gradle.kts
git commit -m "Android project skeleton + RootEncoder dependency"

git add app/src/main/AndroidManifest.xml app/src/main/java/com/movozen/dashcam/Config.kt
git commit -m "Permissions, foreground service declaration, RTMP endpoint config"

git add app/src/main/java/com/movozen/dashcam/StreamService.kt
git commit -m "Foreground streaming service: Camera2+Mic -> H.264/AAC -> RTMP, reconnect, dual feed"

git add app/src/main/java/com/movozen/dashcam/MainActivity.kt app/src/main/res
git commit -m "Operator UI: permissions, preview lifecycle, status, event log"

git add README.md RUN_ME_FIRST.md
git commit -m "Docs"
```

Then after each live test, commit again ("Verified Tier 1 live on server", "Tier 2 dual feed",
etc). Timestamped commits across the two hours look exactly like what they are: your own work.

## 2. Push

```bash
git branch -M main
git remote add origin https://github.com/<your-username>/mobosafe-pocket-dashcam.git
git push -u origin main
```

Create the repo on github.com first (Public — they must be able to open it without a login).

## 3. On the form

- Repo URL: `https://github.com/<you>/mobosafe-pocket-dashcam`
- Roll number: BTECH2502322
- Stream names: `BTECH2502322_front`, `BTECH2502322_back`
- Note the library: "RootEncoder (Apache-2.0) used as an encoding/RTMP library; application code
  written for this challenge."

Be explicit about the library. Declaring a dependency is normal engineering; a judge finding an
undeclared one is not. What is banned is shipping an existing Play Store app, which this is not.
