# Meteor Rejects (Minecraft 26.3 build)

`meteor-rejects-addon-0.3-mc26.3.jar` is a build of a third-party port of
[Meteor Rejects](https://github.com/AntiCope/meteor-rejects). It is not part of this repository's own code.

| | |
| --- | --- |
| Source | https://github.com/Lyriya-928/meteor-rejects-26.2/tree/mc-26.3 |
| Commit | `29cf3f3` (on top of upstream AntiCope/meteor-rejects `6a56030`) |
| License | GPL-3.0 (see `LICENSE`); the corresponding source is at the link above |
| Local change | `oresim-compat.patch`: `HeightContextMixin` uses `@WrapOperation` instead of `@Redirect`, so it no longer crashes at startup together with the standalone OreSim mod (both redirected the same calls in `WorldGenerationContext`) |
| Built with | Temurin JDK 25, `./gradlew build` |
| SHA-256 | `366fa0f8d469e5702c69c4f7cf0719c1a04d9ec82a88d54b2659753a8ef20140` |

Requires Minecraft 26.3, Fabric Loader, Fabric API, Meteor Client 26.3 and Java 25.
