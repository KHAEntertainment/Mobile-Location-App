# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for a security vulnerability.**

Report it privately through GitHub's private vulnerability reporting:

> **[Security tab](https://github.com/KHAEntertainment/Mobile-Location-App/security) → Report a
> vulnerability**

That channel is visible only to the maintainers, and it lets us discuss and fix the issue before any
detail becomes public.

**What to include.** The more of this you can give us, the faster we can act:

- What the issue is, and what an attacker gets out of it.
- Reproduction steps, or a proof of concept.
- The **app version** (`versionName`, e.g. `0.2.0`) or the commit SHA you tested.
- Your **Android version** and — this matters more than the OS version for anything WebView-related
  — your **installed Android System WebView version** (Settings → Apps → Android System WebView).
- The device, if the behaviour looks device-specific.

**What to expect.** This is a small project with no paid security team and no bug-bounty program. We
aim to acknowledge a report within **7 days** and to give you an assessment — accepted, needs more
information, or out of scope with reasoning — within **30 days**. We will credit you in the fix
notes unless you would rather stay anonymous.

## Supported versions

Only the current `main` branch and the most recent release are supported. This project is
pre-1.0 and distributed as a sideloadable APK; there is no maintained release branch and no
backporting. Fixes land on `main`.

---

## What this app actually is

Reading this section first will save you time, because a large class of report is out of scope
purely because of what this app is.

GeoAlign Browser aligns the location signals a *web page* can see — geolocation, timezone, locale,
user-agent — with the apparent exit of a VPN **the user is already running themselves**. Three
constraints hold, by design:

1. **It does not implement or operate a VPN.** It does not tunnel, route, or proxy your traffic.
   Whatever network path your traffic takes is the one your own VPN client (or lack of one) gives
   it. This app only *observes* that a VPN transport exists.
2. **It does not change Android's system GPS.** The app holds no location permission. Other apps on
   your device are entirely unaffected. Everything happens inside an embedded WebView.
3. **It makes no anonymity guarantee.** It is a consistency tool, not a cloak.

## In scope

Reports about the app failing at what it does claim, or introducing a risk of its own:

- **Real device location reaching a page.** Genuine GPS coordinates, or the device's true
  coordinates from any source, becoming visible to loaded web content. This is the single highest
  severity class in the project.
- **Secret material leaking.** The user-supplied IP-geolocation API key escaping Android Keystore,
  being written to logs, or being sent anywhere other than the provider it belongs to.
- **Local-network access that `LocalNetworkPolicy` should have blocked** — reaching a private-range
  address, a `.local` host, or `localhost` from loaded content, including through redirect chains,
  alternative IP notations, DNS rebinding, or service workers. (See the known-limitation note
  below.)
- **Breaking out of the WebView boundary** — content escaping to native code, reading app-private
  files, abusing a JavaScript bridge, or exploiting the internal-origin diagnostics surface.
- **Transport security failures** — accepting an invalid TLS certificate, cleartext traffic, mixed
  content being loaded, or an outbound request going somewhere the app did not intend.
- **Cross-session leakage that contradicts the UI** — "clear session" or a temporary session leaving
  behind cookies, storage, or history that the app told the user were gone.
- **A protection reported as active that is not.** If the UI says a protection is on while the
  installed WebView cannot actually deliver it, that is a bug we want, because the whole design
  rests on never claiming an unverified protection.
- **Permission creep.** Any location, camera, microphone, wifi-state, or bluetooth permission
  appearing in the merged manifest, including transitively from a dependency.
- **Vulnerable dependencies** — a known CVE in a component listed in
  [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md) that is reachable in this app.

## Out of scope

These are not vulnerabilities in this project. They describe the app working as designed, or a
component this project does not control.

- **"This app didn't tunnel my traffic."** / "My IP address was still visible." / "My ISP could see
  my connection." / "It doesn't route through Tor." The app **does not implement a VPN** and never
  claims to. It aligns what the browser *reports* with the VPN you are running. If you are not
  running a VPN, your real network path is your real network path. **Reports in this class will be
  closed as out of scope.**
- **"I got fingerprinted anyway."** / "A site detected the spoofing." / "This isn't real anonymity."
  Correct, and documented. The app makes **no anonymity guarantee**. The injected shims run in the
  page's *main* JavaScript world rather than an isolated world, which makes them detectable by
  design and best-effort by nature. A page that identifies the browser as GeoAlign, notices an
  overridden API, or correlates you by some other signal is not a vulnerability report.
- **"My other apps still see my real GPS."** / "Maps still knows where I am." Yes. The app **does not
  change Android's system GPS** and affects nothing outside its own WebView. This is a deliberate
  and load-bearing property, not a gap.
- **Known and documented limitations.** Some Chromium network paths — certain WebSocket and
  pre-resolved connections — are not fully interceptable from an app-level WebView, so local-network
  blocking is not total. Timezone virtualization cannot perfectly reproduce a full tz database at
  the JS layer, and some deep `Date`/`Intl` paths may disagree. True simultaneous per-profile
  storage isolation is not reliably achievable app-side in Android WebView. These are described in
  [`docs/ARCHITECTURE_PLAN.md`](docs/ARCHITECTURE_PLAN.md) §9, §11, and §12. A report that
  *demonstrates a specific new bypass* beyond what is documented **is** in scope — a report that
  restates the documented limitation is not.
- **Vulnerabilities in the Android System WebView itself.** It is an operating-system component
  provided and updated by the device vendor or Google Play. Report those to
  [Chromium](https://issues.chromium.org/) or the vendor.
- **Vulnerabilities in the third-party network services** the app calls (`ipify.org`,
  `icanhazip.com`, `ipwho.is`, `ipinfo.io`). Report those to the operator.
- **Attacks requiring a rooted device, a physical attacker with an unlocked device, a malicious
  device administrator, or a compromised OS.** Those are outside this threat model.
- **Social engineering, phishing, or reports against the maintainers' accounts or infrastructure**
  rather than this codebase.
- **Automated scanner output with no demonstrated impact**, and missing-hardening findings with no
  exploit path.

## Threat model in one line

Loaded web content is **hostile**. The device, the OS, the user's VPN client, and the user
themselves are **trusted**. Everything in scope above is a way for hostile content to learn
something it should not, or to reach something it should not.

## Disclosure

We ask for coordinated disclosure: give us a reasonable window to ship a fix before publishing.
Given the project's size, **90 days** is the default. If a report is genuinely being ignored, say so
in the thread — a slow maintainer is a reason to escalate, not a reason for us to object to eventual
publication.
