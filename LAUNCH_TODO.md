# Play Store Launch TODO

Ordered checklist for getting Battery Alert onto the Play Store.
Security + policy audits passed (2026-07-25); targetSdk 35, no specialUse FGS, no INTERNET.

## 1. Code (Claude can do)

- [ ] Adaptive launcher icon — mipmap adaptive-icon (foreground/background/monochrome) replacing the plain vector drawable, so launchers mask it properly and themed icons work
- [ ] Full-screen-intent fallback — runtime `canUseFullScreenIntent()` status in the Permissions card with a settings deep-link, since Play/user can revoke FSI

## 2. Build & signing (Manish — involves passwords)

- [ ] Create upload keystore: Android Studio → Build → Generate Signed App Bundle → Create new… Keep it OUT of the repo, back it up (losing it hurts)
- [ ] Wire `signingConfig` into `app/build.gradle`, credentials in `~/.gradle/gradle.properties` (never committed) — Claude can do the gradle side once the keystore exists
- [ ] `./gradlew bundleRelease` → upload `.aab` (Play requires AAB, not APK); bump versionCode/versionName per release
- [ ] One release-build smoke test on the phone (minified builds can differ): install release variant, run the 2-minute alarm test (`dumpsys battery set status 3` / `set level 18` / reopen app)

## 3. Play Console (Manish — account + forms)

- [ ] Developer account at play.google.com/console ($25 one-time; identity verification takes days — start early)
- [ ] Privacy policy URL (required) — one paragraph: no INTERNET permission, nothing collected, nothing leaves the device. GitHub Pages works
- [ ] Create app → upload AAB to **Internal testing** first (installable in minutes, no review)
- [ ] Data Safety form: "no data collected" (true — no INTERNET permission)
- [ ] Content rating questionnaire (~5 min, utility app)
- [ ] Full-screen intent declaration — justification: battery alarm requiring immediate attention
- [ ] Store listing: title (note "Battery Alert" is a crowded name — consider a distinctive one), short + full description (lead with provable privacy), 2+ phone screenshots, 512px icon, 1024×500 feature graphic

## 4. Testing gate → production

- [ ] New personal accounts: **12+ testers for 14 days in closed testing** before production is allowed — the longest pole; recruit early
- [ ] Promote to production; review typically a few days (FSI + exact-alarm declarations may get a human look — the honest justifications hold)

**Realistic timeline:** code + build in a day; account verification a few days; the 14-day testing gate dominates → ~3 weeks to public.
