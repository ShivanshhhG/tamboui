# Releasing TamboUI

Releases are automated by the **`Release`** GitHub Actions workflow
(`.github/workflows/release.yml`). You do **not** write a changelog by hand and
you do **not** run Gradle publishing locally — the workflow does everything.

The release is a **two-step** process on purpose: the `Release` workflow publishes
artifacts and docs and creates the GitHub release **as a draft**, so you can review
and edit the generated notes. **Publishing that draft** is what fires the social
announce (so you never announce notes you haven't looked at).

## TL;DR

```bash
# 1. from anywhere with gh + repo access
gh workflow run release.yml \
  -f version=0.7.0 \
  -f next-version=0.8.0-SNAPSHOT
```

Or: GitHub → **Actions → Release → Run workflow**, fill in `version` and
`next-version`.

```
# 2. review & edit the DRAFT release notes on GitHub, then click "Publish release".
#    Publishing fires the announce (Zernio). Nothing announces until you publish.
```

That's the whole release. Everything below is context and pre-flight.

## Do I need to write a changelog?

**No.** JReleaser generates the GitHub release notes automatically from commit
history using the **conventional-commits** preset (`jreleaser.yml`). Merge
commits, `dependabot`, and all-contributors bookkeeping are filtered out.

The only thing you owe the changelog is **good commit / PR titles**:

- Use conventional-commit prefixes: `feat:`, `fix:`, `docs:`, `test:`,
  `refactor:`, `chore:`, `perf:`, `build:`.
- The PR title becomes the changelog line (squash-merge keeps it clean).
- Anything without a recognized prefix still appears but lands uncategorized.

Because the release is created as a **draft**, editing the generated notes is a
normal part of the flow: add a human-friendly "Highlights" summary at the top,
then publish. The auto-changelog below your summary needs no editing.

## What the `Release` workflow does (in order)

1. **Bump to final** — sets `version=<version>` in `gradle.properties`, commits
   `[release] Releasing version <version>`, pushes to `main`.
2. **Publish to Maven Central** — `./gradlew publishToSonatype
   closeAndReleaseSonatypeStagingRepository` (GPG-signed). *(This is the real
   artifact publish — JReleaser does not deploy artifacts in this repo.)*
3. **GitHub release (DRAFT)** — JReleaser `release` (not `full-release`): tag +
   **draft** release + auto changelog. It does **not** announce.
4. **Publish docs** — triggers `documentation.yml`.
5. **Bump to next** — sets `version=<next-version>`, commits
   `[release] Bumping version to <next-version>`, pushes to `main`.

There is **no manual approval gate** once triggered, and it pushes to `main`
twice. Make sure `main` is green and ready before you fire it. The checkout uses
`fetch-depth: 0` so JReleaser can diff from the last tag for a complete changelog.

## Publishing the draft (this triggers the announce)

When the `Release` workflow finishes, the GitHub release exists **as a draft**
with the full auto-changelog. Then:

1. Open the draft (GitHub → **Releases**), review the notes, add a Highlights
   summary if you want.
2. Click **Publish release**.
3. Publishing fires the `release: released` event, which runs the **`Announce`**
   workflow (`.github/workflows/announce.yml`) → JReleaser `announce` → Zernio
   posts to every connected account.

**Artifacts and docs are already live at this point** — publishing the draft
only controls when the social announce goes out. If you need to announce a past
version (or the auto-announce didn't fire), run **Actions → Announce → Run
workflow** with the `version` (e.g. `0.6.0`).

## Pre-release checklist

- [ ] Target milestone is empty (all intended PRs/issues merged or moved).
- [ ] `main` CI is green (`gradle.yml`).
- [ ] Merged PR titles use conventional-commit prefixes (drives the changelog).
- [ ] Decide the two versions:
  - `version` = release, e.g. `0.5.0` (just drop `-SNAPSHOT` from the current
    `gradle.properties`).
  - `next-version` = next dev cycle, e.g. `0.6.0-SNAPSHOT`.

## Required repository secrets

The publish step is skipped if `CENTRAL_USERNAME` is unset, so these must exist:

- `CENTRAL_USERNAME`, `CENTRAL_PASSWORD` — Maven Central (Sonatype) portal creds.
- `GPG_SIGNING_KEY`, `GPG_SIGNING_PASSWORD` — artifact signing.
- `GITHUB_TOKEN` — provided by Actions (release + docs trigger).
- `ZERNIO_TOKEN`, `ZERNIO_PROFILE_ID` — social announce (see below). Optional:
  if unset, the Zernio announcer just fails/skips; the rest of the release is
  unaffected.

## Social announce (Zernio)

The announce runs via JReleaser's native **`zernio`** announcer
(`announce.zernio` in `jreleaser.yml`), which posts to every account connected
to the configured Zernio profile — one message, all platforms. It's
`active: RELEASE`, so snapshots/prereleases don't announce.

**It runs from `announce.yml`, not the `Release` workflow** — triggered when you
publish the draft GitHub release (or via its manual `workflow_dispatch`). This is
deliberate: you always eyeball the release notes before anything is announced.

- Set repo secrets `ZERNIO_TOKEN` (API key) and `ZERNIO_PROFILE_ID` (the profile
  whose connected accounts should receive the post). These are read by
  `announce.yml` as `JRELEASER_ZERNIO_TOKEN` / `JRELEASER_ZERNIO_PROFILE_ID`.
- The message lives in `jreleaser.yml` (`announce.zernio.message`); for richer
  posts drop a template at `src/jreleaser/templates/zernio.tpl`.
- To include a video/image, upload it via Zernio and reference it — the
  announcer posts text by default.

## After the `Release` workflow (while the release is still a draft)

- Verify the artifacts on Maven Central (`dev.tamboui`) — Central publishing can
  take a while to propagate.
- Verify the **draft** GitHub Release + tag exist and the changelog looks right.
- Confirm the docs site rebuilt (`documentation.yml` run).
- Confirm `gradle.properties` on `main` is back to `<next-version>`.
- **Review/edit the draft notes, then Publish** — this fires the announce.
- After publishing: confirm the `Announce` workflow ran and the Zernio post went out.

## If something goes wrong

- **Publish step skipped** → `CENTRAL_USERNAME` secret missing.
- **Central close/release failed** → check the staging repo in the Sonatype
  portal; the GitHub release may still have been created. Re-running is safest
  from a clean state (the `release.github.overwrite: true` setting lets JReleaser
  recreate the GitHub release).
- **Wrong version pushed** → fix `gradle.properties` on `main` with a follow-up
  commit; drop/re-tag as needed.
