# Release flow: PR-gated tagging

This repo releases by pushing a `v*` git tag, which triggers two workflows:

- [`release.yml`](../.github/workflows/release.yml) — builds the native `libccl` tarballs and
  creates the GitHub Release.
- [`publish-js.yml`](../.github/workflows/publish-js.yml) — publishes the npm packages via OIDC
  trusted publishing.

A tag push is therefore an **irreversible public release**. To keep that trustworthy without making
a human hand-push tags, tag creation is automated behind a reviewed pull request.

## The two steps

1. **Propose** — anyone opens a PR that bumps `version` in `gradle.properties` (the single source of
   truth for the version). The PR diff is the release proposal and the audit trail.
2. **Approve → auto-tag** — the PR can only be approved by a **release code owner**
   (`@satran004`, `@matiwinnetou`, `@fabianbormann`). On merge to `main`,
   [`tag-release.yml`](../.github/workflows/tag-release.yml) reads the new version and pushes the
   matching `v<version>` tag, which triggers `release.yml` + `publish-js.yml`. The npm publish then
   pauses on the `npm-release` environment for a final approval before it goes out.

Direct `v*` tag pushes are blocked (see the tag ruleset below), so a release can **only** originate
from an approved PR.

## Why a GitHub App token (not `GITHUB_TOKEN`)

`tag-release.yml` pushes the tag with a **GitHub App token**, not the default `GITHUB_TOKEN`.
GitHub deliberately does **not** trigger `on: push` workflows for refs pushed by `GITHUB_TOKEN` (a
recursion guard). If the tag were pushed with `GITHUB_TOKEN`, neither `release.yml` nor
`publish-js.yml` would fire. The App token is a normal actor, so the tag push fans out normally.

## The enforcement layers

| Layer | What it enforces | Where |
|---|---|---|
| `CODEOWNERS` | Version bumps and pipeline changes require a release-code-owner review | [`.github/CODEOWNERS`](../.github/CODEOWNERS) |
| `main` branch ruleset | PR required, ≥1 approval, **code-owner review required** | GitHub repo settings |
| `v*` tag ruleset | Only the release App may create `v*` tags | GitHub repo settings |
| `npm-release` environment | A reviewer must approve immediately before npm publish | GitHub repo settings |

`CODEOWNERS` only decides *who must approve* — it does **not** grant anyone the ability to skip the
PR. Skipping comes solely from a ruleset's **bypass list**. So the bypass lists must stay tight:

- **`main` ruleset bypass list: empty** (or at most one break-glass admin). Do **not** add the
  `Maintain` or `Write` roles — anyone on the bypass list skips code-owner review entirely.
- **`v*` tag ruleset bypass list: the release App only** (optionally org owners as break-glass).
  Excluding `Maintain`/`Write` is what stops a maintainer from pushing a `v*` tag directly and
  releasing outside the PR flow.

Note: repository admins and organization owners can always edit or disable a ruleset, so they are an
unavoidable break-glass. Everyone at `Maintain` and below is fully bound.

## How to cut a release

1. Open a PR that bumps `version` in `gradle.properties`, and — per
   [RELEASING.md](../RELEASING.md) — the in-lockstep wrapper constants (Rust
   `DEFAULT_LIB_VERSION`/`Cargo.toml`, Go `defaultLibVersion`/`expectedLibVersion`, Python/JS
   `EXPECTED_LIB_VERSION`). The JS `package.json` version is stamped by CI and needs no manual edit.
2. A release code owner reviews and merges to `main`.
3. `tag-release.yml` pushes `v<version>`; `release.yml` and `publish-js.yml` start automatically.
4. A release code owner approves the `npm-release` environment when `publish-js.yml` pauses. The npm
   packages publish (pre-release versions land under a non-`latest` dist-tag) and the GitHub Release
   is created.
5. For the Go module, push the separate `wrappers/go/v<version>` tag (still manual — see
   RELEASING.md).

## One-time setup (repository admin)

See the "Manual repo settings" section referenced from RELEASING.md, or the checklist that
accompanied this change: provision the GitHub App + `RELEASE_APP_ID` / `RELEASE_APP_PRIVATE_KEY`
secrets, create the `main` and `v*` rulesets with the bypass lists above, and configure the
`npm-release` environment's required reviewers.
