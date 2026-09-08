# Lane Command — complete rebuild

A fresh Android lane-shooter prototype built around the supplied gameplay video rather than the previous arena prototype.

## Gameplay
- Drag left/right to steer a growing blue squad.
- The squad fires automatically at high speed.
- Blue gates add or multiply soldiers; red gates subtract soldiers.
- Numbered barrels/crates absorb shots. Break them before they pass to win strong rewards.
- Enemy crowds have visible counts and are reduced by gunfire.
- Spiked road obstacles punish bad lane choices.
- A larger central commander is embedded in the squad.
- Bosses appear regularly and fire back.
- City-road scenery, crosswalks, lamps, storefronts, water/bridge edges, hit sparks and explosions.

## Stability changes
The old threaded game loop has been removed. The whole simulation now updates on the Android UI frame callback. Entity counts are capped and off-screen objects are removed deterministically to prevent runaway bullets/particles/enemies.

## Monetisation
None. No ads, no purchases, no internet permission.

See `BUILD-IN-BROWSER.md` for the easiest APK build method.
