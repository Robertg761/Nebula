#!/usr/bin/env node

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const repoRoot = path.resolve(__dirname, "..");
const realRepoRoot = fs.realpathSync(repoRoot);
const statusPath = path.join(repoRoot, "release", "supply-chain-status.json");

function fail(message) {
  console.error(`Release supply-chain gate: ${message}`);
  process.exit(1);
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    fail(`cannot parse ${path.relative(repoRoot, file)}: ${error.message}`);
  }
}

function checkedRepositoryFile(record, label) {
  if (!record || typeof record !== "object") {
    fail(`${label} record is missing`);
  }
  if (
    typeof record.path !== "string" ||
    record.path.length === 0 ||
    path.isAbsolute(record.path) ||
    record.path.split(/[\\/]/).includes("..")
  ) {
    fail(`${label}.path must be a repository-relative path`);
  }
  if (!/^[0-9a-f]{64}$/.test(record.sha256 || "")) {
    fail(`${label}.sha256 must be a lowercase SHA-256 digest`);
  }

  const resolved = path.resolve(repoRoot, record.path);
  if (!fs.existsSync(resolved)) {
    fail(`${label} file is missing: ${record.path}`);
  }
  const stat = fs.lstatSync(resolved);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size === 0) {
    fail(`${label} must be a non-empty regular file, not a symlink`);
  }
  const real = fs.realpathSync(resolved);
  const relativeRealPath = path.relative(realRepoRoot, real);
  if (
    relativeRealPath === "" ||
    relativeRealPath.startsWith(`..${path.sep}`) ||
    path.isAbsolute(relativeRealPath)
  ) {
    fail(`${label} resolves outside the repository`);
  }

  const digest = crypto
    .createHash("sha256")
    .update(fs.readFileSync(real))
    .digest("hex");
  if (digest !== record.sha256) {
    fail(`${label} checksum does not match ${record.path}`);
  }
  return real;
}

function normalizedBomComponents(document, format, label) {
  if (format === "cyclonedx-json") {
    if (
      document.bomFormat !== "CycloneDX" ||
      typeof document.specVersion !== "string" ||
      !Array.isArray(document.components) ||
      document.components.length === 0
    ) {
      fail(`${label} is not a non-empty CycloneDX JSON BOM`);
    }
    return document.components.map((component) => ({
      name: String(component.name || ""),
      version: String(component.version || ""),
      licenses: JSON.stringify(component.licenses || []).toLowerCase(),
      hashes: JSON.stringify(component.hashes || []).toLowerCase(),
      source: JSON.stringify(component.externalReferences || []).toLowerCase(),
    }));
  }

  if (
    typeof document.spdxVersion !== "string" ||
    !document.spdxVersion.startsWith("SPDX-") ||
    document.SPDXID !== "SPDXRef-DOCUMENT" ||
    !Array.isArray(document.packages) ||
    document.packages.length === 0
  ) {
    fail(`${label} is not a non-empty SPDX JSON document`);
  }
  return document.packages.map((component) => ({
    name: String(component.name || ""),
    version: String(component.versionInfo || ""),
    licenses: [
      component.licenseConcluded,
      component.licenseDeclared,
    ].filter(Boolean).join(" ").toLowerCase(),
    hashes: JSON.stringify(component.checksums || []).toLowerCase(),
    source: JSON.stringify({
      downloadLocation: component.downloadLocation,
      sourceInfo: component.sourceInfo,
      externalRefs: component.externalRefs,
    }).toLowerCase(),
  }));
}

function checkedJsonBom(record, label, requiredComponents) {
  if (!["cyclonedx-json", "spdx-json"].includes(record?.format)) {
    fail(`${label}.format must be cyclonedx-json or spdx-json`);
  }
  const bomPath = checkedRepositoryFile(record, label);
  const document = readJson(bomPath);
  const components = normalizedBomComponents(document, record.format, label);

  if (!Array.isArray(requiredComponents) || requiredComponents.length === 0) {
    fail(`${label} has no independently reviewed requiredComponents`);
  }
  const requiredIdentities = new Set(
    requiredComponents.map((component) =>
      `${String(component?.name || "").toLowerCase()}@${String(component?.version || "")}`,
    ),
  );
  if (requiredIdentities.size !== requiredComponents.length) {
    fail(`${label}.requiredComponents must contain distinct component identities`);
  }
  for (const expected of requiredComponents) {
    if (
      !expected ||
      typeof expected.name !== "string" ||
      expected.name.trim() === "" ||
      typeof expected.version !== "string" ||
      expected.version.trim() === ""
    ) {
      fail(`${label} has an invalid required component`);
    }
    const match = components.find(
      (component) =>
        component.name.toLowerCase() === expected.name.toLowerCase() &&
        (
          expected.version == null ||
          component.version === String(expected.version)
        ),
    );
    if (!match) {
      fail(
        `${label} does not contain required component ${expected.name}` +
          `${expected.version ? ` ${expected.version}` : ""}`,
      );
    }
    if (
      expected.license &&
      !match.licenses.includes(String(expected.license).toLowerCase())
    ) {
      fail(`${label} does not record the reviewed license for ${expected.name}`);
    }
    if (
      expected.sha256 &&
      !match.hashes.includes(String(expected.sha256).toLowerCase())
    ) {
      fail(`${label} does not record the reviewed binary hash for ${expected.name}`);
    }
    if (
      expected.source &&
      !match.source.includes(String(expected.source).toLowerCase())
    ) {
      fail(`${label} does not record the reviewed source for ${expected.name}`);
    }
  }
  return bomPath;
}

function checkedNativeComponents(components) {
  if (!Array.isArray(components) || components.length < 3) {
    fail("nativeComponents must inventory libmpv-android, mpv, and FFmpeg");
  }
  const requiredNames = new Set(["libmpv-android", "mpv", "ffmpeg"]);
  const seenNames = new Set();
  for (const component of components) {
    const name = String(component?.name || "");
    const normalizedName = name.toLowerCase();
    if (seenNames.has(normalizedName)) {
      fail(`nativeComponents contains duplicate component ${name}`);
    }
    seenNames.add(normalizedName);
    requiredNames.delete(normalizedName);
    for (const field of ["version", "source", "license"]) {
      if (typeof component?.[field] !== "string" || component[field].trim() === "") {
        fail(`nativeComponents.${name || "unknown"}.${field} is incomplete`);
      }
    }
    if (!/^https:\/\//.test(component.source)) {
      fail(`nativeComponents.${name}.source must be an HTTPS source reference`);
    }
    if (!/^[0-9a-f]{64}$/.test(component.sha256 || "")) {
      fail(`nativeComponents.${name}.sha256 must identify the reviewed binary`);
    }
  }
  if (requiredNames.size > 0) {
    fail(`nativeComponents is missing: ${[...requiredNames].join(", ")}`);
  }
  return components;
}

function checkedSourceArchive(record, nativeComponents) {
  if (record?.format !== "tar.gz") {
    fail("nativeSourceArchive.format must be tar.gz");
  }
  if (
    !Array.isArray(record.requiredEntries) ||
    record.requiredEntries.length !== nativeComponents.length
  ) {
    fail("nativeSourceArchive.requiredEntries must map exactly one entry to every native component");
  }
  const archivePath = checkedRepositoryFile(record, "nativeSourceArchive");
  const listing = spawnSync("tar", ["-tzf", archivePath], {
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  if (listing.error || listing.status !== 0) {
    fail(`nativeSourceArchive is not a readable tar.gz: ${listing.stderr || listing.error}`);
  }
  const entries = listing.stdout.split(/\r?\n/).filter(Boolean);
  if (entries.length === 0 || entries.length > 100_000) {
    fail("nativeSourceArchive has an empty or unreasonably large entry list");
  }
  for (const entry of entries) {
    if (
      path.posix.isAbsolute(entry) ||
      entry.split("/").includes("..") ||
      entry.includes("\\")
    ) {
      fail(`nativeSourceArchive contains an unsafe path: ${entry}`);
    }
  }
  const verboseListing = spawnSync("tar", ["-tvzf", archivePath], {
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  if (verboseListing.error || verboseListing.status !== 0) {
    fail("nativeSourceArchive metadata could not be inspected");
  }
  if (
    verboseListing.stdout
      .split(/\r?\n/)
      .some((line) => line.startsWith("l") || line.startsWith("h"))
  ) {
    fail("nativeSourceArchive must not contain symbolic or hard links");
  }
  const componentsWithoutEntry = new Set(
    nativeComponents.map((component) => component.name.toLowerCase()),
  );
  const requiredPaths = new Set();
  for (const required of record.requiredEntries) {
    const component = String(required?.component || "").toLowerCase();
    const requiredPath = required?.path;
    if (!componentsWithoutEntry.delete(component)) {
      fail("nativeSourceArchive.requiredEntries has an unknown or duplicate component");
    }
    if (
      typeof requiredPath !== "string" ||
      requiredPath.length === 0 ||
      path.posix.isAbsolute(requiredPath) ||
      requiredPath.split("/").includes("..") ||
      requiredPath.includes("\\")
    ) {
      fail("nativeSourceArchive has an invalid requiredEntries path");
    }
    const normalizedRequiredPath = path.posix
      .normalize(requiredPath)
      .replace(/\/+$/, "");
    if (
      normalizedRequiredPath === "" ||
      normalizedRequiredPath === "." ||
      requiredPaths.has(normalizedRequiredPath)
    ) {
      fail("nativeSourceArchive has a duplicate requiredEntries path");
    }
    requiredPaths.add(normalizedRequiredPath);
    const prefix = `${normalizedRequiredPath}/`;
    if (
      !entries.includes(normalizedRequiredPath) &&
      !entries.some((entry) => entry.startsWith(prefix))
    ) {
      fail(`nativeSourceArchive is missing reviewed entry ${normalizedRequiredPath}`);
    }
  }
  if (componentsWithoutEntry.size > 0) {
    fail(
      `nativeSourceArchive is missing component mappings: ` +
        `${[...componentsWithoutEntry].join(", ")}`,
    );
  }
  return archivePath;
}

const status = readJson(statusPath);
if (status.schemaVersion !== 1) {
  fail("unsupported status schema");
}
if (status.releaseReady !== true) {
  const blockers = Array.isArray(status.blockers) ? status.blockers : [];
  console.error("Public release is intentionally blocked:");
  for (const blocker of blockers) {
    console.error(`- ${blocker}`);
  }
  fail("reviewed Gradle/native SBOMs and corresponding source are incomplete");
}

const nativeComponents = checkedNativeComponents(status.nativeComponents);
if (
  !Array.isArray(status.gradleSbom?.requiredComponents) ||
  status.gradleSbom.requiredComponents.length < 3
) {
  fail("gradleSbom.requiredComponents must contain the reviewed dependency sentinels");
}
const releaseFiles = [
  checkedJsonBom(
    status.gradleSbom,
    "gradleSbom",
    status.gradleSbom.requiredComponents,
  ),
  checkedJsonBom(status.nativeSbom, "nativeSbom", nativeComponents),
  checkedSourceArchive(status.nativeSourceArchive, nativeComponents),
];
const releaseNames = new Set(releaseFiles.map((file) => path.basename(file)));
if (releaseNames.size !== releaseFiles.length) {
  fail("release SBOM/source assets must have distinct filenames");
}

console.log("Release supply-chain gate passed.");
