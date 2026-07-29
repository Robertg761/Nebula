# Required GitHub repository settings

Workflow files cannot enforce these controls by themselves. Apply this checklist
in GitHub after the branch lands; none of these remote settings are changed by
the source patch.

## Protect `main`

Create a branch ruleset for `main`:

- require a pull request before merge;
- require the latest approval after new commits when more than one maintainer is
  available;
- require conversation resolution;
- require branches to be up to date;
- require these checks:
  - `android-host`
  - `TV instrumentation (26)`
  - `TV instrumentation (34)`
  - `dependency-review`
- block force pushes and deletion;
- restrict bypass to emergency maintainers and audit every bypass.

For a single-maintainer repository, required CI is still valuable even if an
approval requirement is temporarily impractical.

## Protect releases

Create an Actions environment named `release`:

- allow deployments only from `main`;
- add a required reviewer;
- store `SS_SIGNING_STORE_BASE64`, `SS_SIGNING_STORE_PASSWORD`,
  `SS_SIGNING_KEY_ALIAS`, `SS_SIGNING_KEY_PASSWORD`, and optional
  `SS_SIGNING_STORE_TYPE` as environment secrets;
- store `SS_SIGNING_CERT_SHA256` as an environment variable;
- prevent self-review when another maintainer is available.

Create a tag ruleset for `v*` that blocks update/deletion except through the
release process.

## Security and maintenance

- keep default workflow-token permissions read-only;
- enable the dependency graph, Dependabot alerts, and private vulnerability
  reporting;
- enable Issues when the bug template is ready for public support;
- retain Actions evidence long enough to cover the supported release window.
