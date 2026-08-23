# Contributing to GeoAlign Browser

Thanks for your interest. This document covers what you need to build and test the project, the
conventions the codebase holds to, and the three product constraints that a contribution can never
weaken.

Read [§1](#1-the-three-constraints) before you write code. Everything else in this file is
mechanics; §1 is the product.

---

## 1. The three constraints

GeoAlign Browser aligns the location signals a *web page* can see with the apparent exit of a VPN
**the user is already running themselves**. Three statements must stay true in code, in UI copy, in
commit messages, and in documentation:

1. **This app does not implement or operate a VPN.** It does not tunnel, route, or proxy traffic. It
   observes that a VPN transport exists and aligns the browser's story with it.
2. **This app does not change Android's system GPS.** The app holds no location permission and never
   will. Other apps on the device are entirely unaffected. Everything is virtualized inside an
   embedded WebView.
3. **This app makes no anonymity guarantee.** It is a consistency tool, not a cloak. A determined
   site can still fingerprint the browser, and the injected shims run in the page's main JavaScript
   world, which makes them detectable by design.

A fourth rule follows from these: **no protection may be reported active unless it was verified at
runtime.** Requesting a WebView feature is not the same as having it. If
`WebViewFeature.isFeatureSupported(...)` says no, the UI says no — it does not claim the protection
and quietly degrade.

Honesty here is a product constraint, not a style preference. A PR whose copy overstates what the
app does will be sent back even if the code is correct.

---

## 2. Toolchain

You need:

| Requirement | Notes |
|---|---|
| **JDK 17–21** | Gradle 8.11.1 / AGP 8.7.3 will not accept a newer JDK. A newer system JDK is fine as long as Gradle is pointed at a supported one. |
| **Android SDK** with `ANDROID_HOME` set | `platforms;android-35` and `build-tools;35.0.0`, matching `compileSdk = 35`. |
| `adb` (optional) | Only needed to install on a device and to use DevTools over adb. |

On macOS with Homebrew, this is the setup the project is developed against:

```bash
brew install openjdk@21 android-commandlinetools android-platform-tools

export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

Both environment variables must be exported in the shell you run Gradle from. Leave your system
default JDK alone and point Gradle at 21 per invocation — that is what the project does.

## 3. Build and test

```bash
# Unit tests — no device or emulator needed. Required before every PR.
./gradlew testDebugUnitTest

# Static analysis
./gradlew lintDebug

# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug
```

**`./gradlew testDebugUnitTest` must pass before you open a pull request.** No exceptions. It runs
entirely on the JVM, needs no hardware, and takes well under a minute. If your change is
documentation-only, run it anyway as a smoke check — an unexpected failure there means something
other than your change is wrong, and that is worth knowing before review.

The full local green gate is:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Installing over a CI-built APK fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` because CI and local
builds use different debug keystores. The `adb uninstall` that fixes it **wipes saved profiles and
the Keystore-held API key** — see [`docs/HANDOFF.md`](docs/HANDOFF.md) §6 to rescue profiles first.

## 4. Source-file header policy

This project is licensed under the **Mozilla Public License 2.0**, which is a *file-level* copyleft.
Every source file needs to declare its license so that the obligation travels with the file when it
is copied out of this repository.

**Put the MPL Exhibit A notice at the top of every source file you create, and on any existing file
you substantially modify.** The text is fixed — do not reword it:

Kotlin, Java, JavaScript (`.kt`, `.java`, `.js`):

```kotlin
/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
```

XML (`.xml`) and HTML (`.html`):

```xml
<!--
  This Source Code Form is subject to the terms of the Mozilla Public
  License, v. 2.0. If a copy of the MPL was not distributed with this
  file, You can obtain one at https://mozilla.org/MPL/2.0/.
-->
```

Gradle Kotlin DSL, shell, properties, TOML (`.kts`, `.sh`, `.properties`, `.toml`):

```
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
```

Rules:

- The header goes **first in the file**, above the `package` declaration or the XML declaration.
- Do **not** add a per-file copyright line naming yourself. Attribution lives in git history and in
  [`NOTICE`](NOTICE). MPL 2.0 does not require a per-file copyright line, and one line per
  contributor per file ages badly.
- Do **not** add the Exhibit B "Incompatible With Secondary Licenses" notice. This project is plain
  MPL 2.0 on purpose, so the code stays compatible with GPL, LGPL, and AGPL as Secondary Licenses.
  Changing that is a project-level decision, not a per-file one.
- Generated files, and files the header genuinely cannot be placed in, are covered by
  [`LICENSE`](LICENSE) at the repository root — MPL 2.0 Exhibit A explicitly allows that fallback.

**Current state, stated plainly:** the 73 Kotlin files already in the tree do **not** carry headers
yet. This policy applies going forward. Backfilling the existing files is a mechanical change worth
doing on its own, not smuggled into an unrelated PR — but if you substantially modify a file, add
the header to it as part of that change.

## 5. Architecture conventions

These are load-bearing. A PR that violates them will be asked to restructure.

**Business logic never lives inside a `@Composable`.** Pure logic goes in `core/` with JVM unit
tests; composables render what a pure presenter returns and nothing more. This is not taste — a
stale-profile bug survived in this codebase specifically because its logic lived inside a
composable, where no test could reach it. If you find yourself writing an `if` that decides
*product behaviour* inside a composable, that decision belongs in `core/` or `ui/state/`.

The layering:

| Package | Contains | Android imports |
|---|---|---|
| `core/` | Reducers, models, policies — pure Kotlin, exhaustively unit-tested | **None** |
| `data/` | Repositories, storage, network — the IO glue | Yes |
| `ui/state/` | Pure presentation logic; presenters that composables render | **None** |
| `web/` | WebView policy and document-start environment injection | Yes |
| `browser/` | Compose UI | Yes |
| `di/` | `AppGraph` — manual DI, no framework | Yes |

Also:

- **New state machines go in `core/` with JVM tests.** No new logic arrives untested.
- **Manual DI through `di/AppGraph.kt`.** No Hilt, no Dagger. New singletons wire in there.
- **Capability facts are produced once and shared.** No surface re-queries `WebViewFeature` on its
  own.
- **Distribution differences flow through an injected `DistributionCapabilities`**, never
  `if (BuildConfig.FLAVOR)` at a call site.
- **minSdk is 26.** Do not use an API above it without a guarded fallback.
- **Permissions are `INTERNET` and `ACCESS_NETWORK_STATE`, and that is the whole list.** Location,
  camera, microphone, wifi-state, and bluetooth permissions are explicitly removed in the manifest.
  Never add one. If a library tries to merge one in transitively, remove it with
  `tools:node="remove"`.

## 6. Workflow

**Work is tracked in GitHub Issues** — one issue per work item, and issues are canonical.

1. Find or open an issue describing the work.
2. Branch from `main` as `feature/<issue-number>-<short-slug>`, e.g.
   `feature/1-license-and-contribution`.
3. Ship **small reviewable slices, each green before you proceed**. A large PR that has to be
   reviewed as one lump is harder to accept than three that each stand alone.
4. Run `./gradlew testDebugUnitTest` (§3).
5. Open a PR whose body contains `Closes #<issue>`, describes the change, and — where the issue
   listed acceptance criteria — walks through each one with real evidence.

**On evidence:** paste actual command output. Never write "tests pass" without the output that shows
it. This project has a documented history of plausible-sounding theories that turned out to be
wrong; a measurement beats a story every time.

**Commits:** imperative subject, and explain *why* in the body when the change is not self-evident.
The existing `git log` is a readable milestone spine — keep it that way.

**CI** runs unit tests, lint, and `assembleDebug` on `main` and on every `feature/**` branch. Treat
edits to `.github/workflows/` as high-care.

## 7. Documentation changes

- Changing a dependency version, adding a dependency, or removing one means updating
  [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md) in the same PR. Nothing generates that file, so it
  only stays honest if you maintain it.
- [`docs/HANDOFF.md`](docs/HANDOFF.md) is the session-to-session state of the project. If you
  resolve something it lists as open, say so there.
- Beware `§n` references in code comments: `spec §n` points at an engineering specification that was
  **never committed to this repository**, while `plan §n` points at
  [`docs/ARCHITECTURE_PLAN.md`](docs/ARCHITECTURE_PLAN.md), which has its own independent numbering.
  They do not map onto each other. Flag ambiguity rather than assert exact spec wording.

## 8. Security issues

Do not open a public issue for a security vulnerability. See [`SECURITY.md`](SECURITY.md) for the
private disclosure route and for what is and is not in scope.

## 9. Licensing of your contribution

By contributing, you agree that your contribution is licensed under the **Mozilla Public License
2.0**, the same license as the project. There is no CLA and no copyright assignment; you keep
copyright in your work.
