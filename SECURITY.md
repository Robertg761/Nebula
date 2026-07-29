# Security policy

## Supported versions

Security fixes are made for the latest published Nebula release. Older
sideloaded APKs should be upgraded before a report is reproduced.

## Reporting a vulnerability

Use GitHub's private vulnerability-reporting flow:

1. Open this repository's **Security** tab.
2. Choose **Report a vulnerability**.
3. Include the affected version, Android/Google TV model and API level,
   reproduction steps, impact, and the smallest safe proof of concept.

Do not put TMDB keys, addon URLs, debrid tokens, stream URLs, pairing URLs,
keystores, signing fingerprints that are not already public, or unredacted
logcat in a report. Configured addon URLs can carry credentials in their path,
userinfo, or query string.

If private reporting is unavailable, contact the maintainer through their
GitHub profile and ask for a private reporting channel. Do not open a public
issue for an unpatched vulnerability.

The maintainer will acknowledge a usable report, reproduce it where possible,
coordinate a fix and release, and credit the reporter if requested. No fixed
response SLA is promised.

## Release trust

Official updates are accepted only when Android verifies the same application
ID and signing lineage as the installed app. The release workflow additionally
checks the configured signing-certificate fingerprint, exact APK name, size,
SHA-256 digest, tag target, and build provenance.

Repository settings that must be configured outside source control are listed
in [docs/repository-settings.md](docs/repository-settings.md).

## Network trust boundary

Nebula treats addon metadata, stream URLs, and subtitle URLs as untrusted.
Initial playback URLs are restricted to canonical public HTTPS addresses.
Subtitle downloads also revalidate redirect targets and resolved addresses.

Native libmpv performs the stream connection after that initial check, including
its own DNS lookups and redirects. Until the policy-enforcing transport work in
the roadmap lands, do not treat initial playback URL validation as complete
protection from DNS rebinding or a redirect to a non-public target. Nebula sends
only `Accept`, `Accept-Language`, and `User-Agent` addon headers to that native
path; credentials, cookies, referrers, and arbitrary custom headers are withheld
so a redirect cannot forward them to another origin.
