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

function readJson(file, label = path.relative(repoRoot, file)) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    fail(`cannot parse ${label}: ${error.message}`);
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

function checkedGenerator(record, label) {
  const file = checkedRepositoryFile(record, label);
  if (!fs.readFileSync(file, "utf8").startsWith("#!/usr/bin/env node\n")) {
    fail(`${label} is not a Node.js generator`);
  }
  return file;
}

function checkedCycloneDx(record, label) {
  if (record?.format !== "cyclonedx-json") {
    fail(`${label}.format must be cyclonedx-json`);
  }
  const file = checkedRepositoryFile(record, label);
  const document = readJson(file, label);
  if (
    document.bomFormat !== "CycloneDX" ||
    document.specVersion !== "1.6" ||
    !Array.isArray(document.components) ||
    document.components.length === 0
  ) {
    fail(`${label} is not a non-empty CycloneDX 1.6 JSON BOM`);
  }
  return { file, document };
}

function propertyValues(component, name) {
  if (!Array.isArray(component?.properties)) return [];
  return component.properties
    .filter((property) => property?.name === name && typeof property.value === "string")
    .map((property) => property.value);
}

function containsLicense(component, expected) {
  return Array.isArray(component?.licenses) && component.licenses.some((entry) =>
    entry?.expression === expected ||
    entry?.license?.id === expected ||
    entry?.license?.name === expected
  );
}

function containsSha256(component, expected) {
  return Array.isArray(component?.hashes) && component.hashes.some((hash) =>
    hash?.alg === "SHA-256" && hash?.content === expected
  );
}

function checkedProjectLicense(record) {
  if (record?.spdx !== "GPL-3.0-or-later") {
    fail("projectLicense.spdx must be GPL-3.0-or-later");
  }
  const file = checkedRepositoryFile(record, "projectLicense");
  const text = fs.readFileSync(file, "utf8");
  if (
    !text.includes("SPDX-License-Identifier: GPL-3.0-or-later") ||
    !text.includes("GNU GENERAL PUBLIC LICENSE") ||
    !text.includes("either version 3 of the License, or (at your option)")
  ) {
    fail("projectLicense does not apply GPL-3.0-or-later to Nebula");
  }
}

function checkedSourceManifest(record, reviewed) {
  const file = checkedRepositoryFile(record, "nativeSourceManifest");
  const manifest = readJson(file, "nativeSourceManifest");
  if (
    manifest.schemaVersion !== 1 ||
    !manifest.bundle ||
    !manifest.build ||
    !Array.isArray(manifest.sources) ||
    manifest.sources.length !== record.sourceCount
  ) {
    fail("nativeSourceManifest has an unsupported or incomplete structure");
  }

  const expectedBundle = {
    mavenCoordinate: reviewed.coordinate,
    version: reviewed.version,
    aarSha256: reviewed.aarSha256,
    effectiveLicense: reviewed.effectiveLicense,
  };
  for (const [field, expected] of Object.entries(expectedBundle)) {
    if (manifest.bundle[field] !== expected) {
      fail(`nativeSourceManifest.bundle.${field} does not match the reviewed bundle`);
    }
  }
  if (
    !Number.isInteger(manifest.bundle.minimumAndroidApi) ||
    !Array.isArray(manifest.bundle.architectures) ||
    manifest.bundle.architectures.length === 0 ||
    new Set(manifest.bundle.architectures).size !== manifest.bundle.architectures.length
  ) {
    fail("nativeSourceManifest bundle platform coverage is incomplete");
  }
  if (
    manifest.build.mpv?.version !== reviewed.mpv.version ||
    manifest.build.mpv?.effectiveOptions?.gpl !== true ||
    !Array.isArray(manifest.build.mpv.flags) ||
    manifest.build.ffmpeg?.version !== reviewed.ffmpeg.version ||
    !Array.isArray(manifest.build.ffmpeg.flags) ||
    !manifest.build.ffmpeg.flags.includes("--enable-gpl") ||
    !manifest.build.ffmpeg.flags.includes("--enable-version3")
  ) {
    fail("nativeSourceManifest does not record the reviewed GPL-enabled mpv/FFmpeg build");
  }

  const names = new Set();
  const archivePaths = new Set();
  for (const source of manifest.sources) {
    if (
      typeof source?.name !== "string" ||
      source.name.trim() === "" ||
      typeof source.version !== "string" ||
      source.version.trim() === "" ||
      typeof source.license !== "string" ||
      source.license.trim() === ""
    ) {
      fail("nativeSourceManifest contains an incomplete source component");
    }
    const normalizedName = source.name.toLowerCase();
    if (names.has(normalizedName)) {
      fail(`nativeSourceManifest contains duplicate source ${source.name}`);
    }
    names.add(normalizedName);
    if (
      typeof source.archivePath !== "string" ||
      source.archivePath.length === 0 ||
      path.posix.isAbsolute(source.archivePath) ||
      source.archivePath.includes("\\") ||
      source.archivePath.split("/").some((part) => !part || part === "." || part === "..") ||
      archivePaths.has(source.archivePath)
    ) {
      fail(`nativeSourceManifest has an unsafe or duplicate path for ${source.name}`);
    }
    archivePaths.add(source.archivePath);
    if (source.type === "git") {
      if (
        !/^https:\/\//.test(source.repository || "") ||
        !/^[0-9a-f]{40}$/.test(source.commit || "")
      ) {
        fail(`nativeSourceManifest does not pin ${source.name} to an HTTPS Git commit`);
      }
    } else if (source.type === "archive") {
      if (
        !/^https:\/\//.test(source.url || "") ||
        !/^[0-9a-f]{64}$/.test(source.sha256 || "") ||
        !Number.isInteger(source.stripComponents) ||
        source.stripComponents < 0
      ) {
        fail(`nativeSourceManifest does not pin ${source.name} archive safely`);
      }
    } else {
      fail(`nativeSourceManifest has unsupported source type for ${source.name}`);
    }
  }

  for (const [key, expected] of Object.entries({
    "libmpv-android": reviewed.libmpvAndroid,
    mpv: reviewed.mpv,
    ffmpeg: reviewed.ffmpeg,
  })) {
    const source = manifest.sources.find((candidate) => candidate.name.toLowerCase() === key);
    if (
      !source ||
      source.version !== expected.version ||
      source.commit !== expected.commit ||
      source.license !== expected.license
    ) {
      fail(`nativeSourceManifest does not match reviewed ${source?.name || key} provenance`);
    }
  }
  return { file, manifest };
}

function checkedGradleBom(record) {
  const { file, document } = checkedCycloneDx(record, "gradleSbom");
  if (
    !Number.isInteger(record.componentCount) ||
    document.components.length !== record.componentCount
  ) {
    fail("gradleSbom component count does not match the reviewed locked graph");
  }
  if (
    propertyValues(document.metadata, "nebula:source-lock")[0] !==
      "apps/android-tv-host/app/gradle.lockfile" ||
    propertyValues(document.metadata, "nebula:verification-metadata")[0] !==
      "apps/android-tv-host/gradle/verification-metadata.xml"
  ) {
    fail("gradleSbom does not identify its lock and verification inputs");
  }

  const refs = new Set();
  for (const component of document.components) {
    if (
      component?.type !== "library" ||
      typeof component.group !== "string" ||
      !component.group ||
      typeof component.name !== "string" ||
      !component.name ||
      typeof component.version !== "string" ||
      !component.version ||
      typeof component["bom-ref"] !== "string" ||
      component["bom-ref"] !== component.purl ||
      !component.purl.startsWith("pkg:maven/") ||
      !Array.isArray(component.licenses) ||
      component.licenses.length === 0
    ) {
      fail("gradleSbom contains an incomplete component");
    }
    if (refs.has(component["bom-ref"])) {
      fail(`gradleSbom contains duplicate component ${component["bom-ref"]}`);
    }
    refs.add(component["bom-ref"]);

    const configurations = propertyValues(component, "nebula:gradle-configuration");
    const verification = propertyValues(component, "nebula:verification-sha256");
    const pomHashes = propertyValues(component, "nebula:pom-sha256");
    const pomCovered = propertyValues(component, "nebula:pom-verification-covered");
    if (
      configurations.length !== 1 ||
      configurations[0] !== "releaseRuntimeClasspath" ||
      verification.length !== 1 ||
      verification[0].split(",").some((hash) => !/^[0-9a-f]{64}$/.test(hash)) ||
      pomHashes.length !== 1 ||
      !/^[0-9a-f]{64}$/.test(pomHashes[0]) ||
      pomCovered.length !== 1 ||
      !["true", "false"].includes(pomCovered[0])
    ) {
      fail(`gradleSbom verification evidence is incomplete for ${component["bom-ref"]}`);
    }
    for (const artifact of propertyValues(component, "nebula:artifact-sha256")) {
      if (!/^[^=]+=([0-9a-f]{64})$/.test(artifact)) {
        fail(`gradleSbom has an invalid artifact digest for ${component["bom-ref"]}`);
      }
    }
    if (
      Array.isArray(component.hashes) &&
      component.hashes.some((hash) =>
        hash?.alg !== "SHA-256" || !/^[0-9a-f]{64}$/.test(hash?.content || "")
      )
    ) {
      fail(`gradleSbom has an invalid component digest for ${component["bom-ref"]}`);
    }
  }

  if (!Array.isArray(record.requiredComponents) || record.requiredComponents.length < 3) {
    fail("gradleSbom.requiredComponents must contain reviewed sentinels");
  }
  for (const expected of record.requiredComponents) {
    const component = document.components.find((candidate) =>
      candidate.group === expected.group &&
      candidate.name === expected.name &&
      candidate.version === expected.version
    );
    if (!component) {
      fail(`gradleSbom is missing ${expected.group}:${expected.name}:${expected.version}`);
    }
    if (expected.license && !containsLicense(component, expected.license)) {
      fail(`gradleSbom license does not match ${expected.group}:${expected.name}`);
    }
  }
  return file;
}

function checkedNativeBom(record, manifest) {
  const { file, document } = checkedCycloneDx(record, "nativeSbom");
  const bundle = document.metadata?.component;
  if (
    bundle?.group !== "dev.jdtech.mpv" ||
    bundle?.name !== "libmpv" ||
    bundle?.version !== manifest.bundle.version ||
    !containsSha256(bundle, manifest.bundle.aarSha256) ||
    !containsLicense(bundle, manifest.bundle.effectiveLicense)
  ) {
    fail("nativeSbom metadata does not identify the reviewed libmpv AAR");
  }
  if (
    propertyValues(document.metadata, "nebula:native-source-manifest")[0] !==
      "release/native-source-manifest.json" ||
    propertyValues(document.metadata, "nebula:mpv-effective-gpl")[0] !== "true"
  ) {
    fail("nativeSbom does not link its source manifest and effective mpv license mode");
  }

  const sourceComponents = document.components.filter((component) => component.type === "library");
  const binaries = document.components.filter((component) => component.type === "file");
  if (
    sourceComponents.length !== manifest.sources.length ||
    sourceComponents.length + binaries.length !== document.components.length
  ) {
    fail("nativeSbom source or binary component coverage is incomplete");
  }
  for (const source of manifest.sources) {
    const component = sourceComponents.find((candidate) =>
      candidate.name === source.name && candidate.version === source.version
    );
    const expectedReference = source.type === "git"
      ? `${source.repository}#${source.commit}`
      : source.url;
    if (
      !component ||
      !containsLicense(component, source.license) ||
      propertyValues(component, "nebula:corresponding-source-path")[0] !== source.archivePath ||
      !Array.isArray(component.externalReferences) ||
      !component.externalReferences.some((reference) =>
        reference?.url === expectedReference &&
        (
          source.type !== "archive" ||
          containsSha256(reference, source.sha256)
        )
      )
    ) {
      fail(`nativeSbom provenance is incomplete for ${source.name}`);
    }
  }

  if (
    !Number.isInteger(record.binariesPerAbi) ||
    record.binariesPerAbi <= 0 ||
    !Array.isArray(record.requiredBinaries) ||
    record.requiredBinaries.length === 0
  ) {
    fail("nativeSbom binary coverage requirements are incomplete");
  }
  const expectedAbis = new Set(manifest.bundle.architectures);
  const byAbi = new Map([...expectedAbis].map((abi) => [abi, new Map()]));
  const refs = new Set();
  for (const component of binaries) {
    const abiValues = propertyValues(component, "nebula:android-abi");
    const entryValues = propertyValues(component, "nebula:aar-entry");
    const sourceValues = propertyValues(component, "nebula:source-component");
    const sizeValues = propertyValues(component, "nebula:size-bytes");
    const abi = abiValues[0];
    const expectedLicense = component.name === "libmpv.so"
      ? "GPL-2.0-or-later"
      : component.name === "libplayer.so"
        ? "MIT"
        : component.name === "libc++_shared.so"
          ? "Apache-2.0 WITH LLVM-exception"
          : "GPL-3.0-or-later";
    const expectedSource = component.name === "libmpv.so"
      ? "mpv"
      : component.name === "libplayer.so"
        ? "libmpv-android"
        : component.name === "libc++_shared.so"
          ? "Android NDK libc++"
          : "FFmpeg";
    const expectedVersion = component.name === "libmpv.so"
      ? manifest.build.mpv.version
      : component.name === "libplayer.so"
        ? manifest.bundle.version
        : component.name === "libc++_shared.so"
          ? manifest.bundle.androidNdkVersion
          : manifest.build.ffmpeg.version;
    if (
      typeof component.name !== "string" ||
      !component.name.endsWith(".so") ||
      component.version !== expectedVersion ||
      component["bom-ref"] !== `native:${abi}/${component.name}` ||
      abiValues.length !== 1 ||
      entryValues.length !== 1 ||
      sourceValues.length !== 1 ||
      sizeValues.length !== 1 ||
      !expectedAbis.has(abi) ||
      entryValues[0] !== `jni/${abi}/${component.name}` ||
      sourceValues[0] !== expectedSource ||
      !/^[1-9][0-9]*$/.test(sizeValues[0]) ||
      !Array.isArray(component.hashes) ||
      component.hashes.length !== 1 ||
      !containsSha256(component, component.hashes[0]?.content) ||
      !/^[0-9a-f]{64}$/.test(component.hashes[0]?.content || "") ||
      !containsLicense(component, expectedLicense)
    ) {
      fail(`nativeSbom has incomplete binary evidence for ${component["bom-ref"] || component.name}`);
    }
    if (refs.has(component["bom-ref"])) {
      fail(`nativeSbom contains duplicate binary reference ${component["bom-ref"]}`);
    }
    refs.add(component["bom-ref"]);
    const abiComponents = byAbi.get(abi);
    if (abiComponents.has(component.name)) {
      fail(`nativeSbom contains duplicate ${abi}/${component.name}`);
    }
    abiComponents.set(component.name, component);
  }
  for (const [abi, components] of byAbi) {
    if (components.size !== record.binariesPerAbi) {
      fail(`nativeSbom has ${components.size} binaries for ${abi}, expected ${record.binariesPerAbi}`);
    }
    for (const required of record.requiredBinaries) {
      if (!components.has(required)) {
        fail(`nativeSbom is missing ${abi}/${required}`);
      }
    }
  }
  const binaryNames = [...byAbi.values()].map((components) =>
    [...components.keys()].sort().join("\n")
  );
  if (new Set(binaryNames).size !== 1) {
    fail("nativeSbom ABI binary sets do not match");
  }
  return file;
}

function checkedSourceArchive(record, manifest, manifestFile) {
  if (record?.format !== "tar.gz") {
    fail("nativeSourceArchive.format must be tar.gz");
  }
  const archive = checkedRepositoryFile(record, "nativeSourceArchive");
  const listing = spawnSync("tar", ["-tzf", archive], {
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
  });
  if (listing.error || listing.status !== 0) {
    fail(`nativeSourceArchive is not a readable tar.gz: ${listing.stderr || listing.error}`);
  }
  const entries = listing.stdout.split(/\r?\n/).filter(Boolean);
  if (
    entries.length !== record.entryCount ||
    entries.some((entry) =>
      path.posix.isAbsolute(entry) ||
      entry.includes("\\") ||
      entry.split("/").includes("..")
    )
  ) {
    fail("nativeSourceArchive entry count or paths do not match the reviewed archive");
  }

  const verbose = spawnSync("tar", ["-tvzf", archive], {
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
  });
  if (
    verbose.error ||
    verbose.status !== 0 ||
    verbose.stdout.split(/\r?\n/).some((line) => line.startsWith("l") || line.startsWith("h"))
  ) {
    fail("nativeSourceArchive contains unreadable metadata or links");
  }

  const root = "Nebula-native-sources";
  for (const required of [
    `${root}/README.txt`,
    `${root}/native-source-manifest.json`,
    ...manifest.sources.map((source) => `${root}/${source.archivePath}`),
  ]) {
    const prefix = `${required.replace(/\/+$/, "")}/`;
    if (!entries.includes(required) && !entries.some((entry) => entry.startsWith(prefix))) {
      fail(`nativeSourceArchive is missing ${required}`);
    }
  }

  const archivedManifest = spawnSync(
    "tar",
    ["-xOzf", archive, `${root}/native-source-manifest.json`],
    { encoding: null, maxBuffer: 4 * 1024 * 1024 },
  );
  if (
    archivedManifest.error ||
    archivedManifest.status !== 0 ||
    !archivedManifest.stdout.equals(fs.readFileSync(manifestFile))
  ) {
    fail("nativeSourceArchive does not contain the reviewed source manifest verbatim");
  }
  return archive;
}

const status = readJson(statusPath);
if (status.schemaVersion !== 2) {
  fail("unsupported status schema");
}
if (status.releaseReady !== true) {
  const blockers = Array.isArray(status.blockers) ? status.blockers : [];
  console.error("Public release is intentionally blocked:");
  for (const blocker of blockers) console.error(`- ${blocker}`);
  fail("reviewed license, SBOMs, and corresponding source are incomplete");
}
if (!Array.isArray(status.blockers) || status.blockers.length !== 0) {
  fail("releaseReady cannot be true while blockers remain");
}

checkedProjectLicense(status.projectLicense);
checkedGenerator(status.generators?.sbom, "generators.sbom");
checkedGenerator(status.generators?.nativeSource, "generators.nativeSource");
const { file: manifestFile, manifest } = checkedSourceManifest(
  status.nativeSourceManifest,
  status.reviewedNativeBundle,
);
const releaseFiles = [
  checkedGradleBom(status.gradleSbom),
  checkedNativeBom(status.nativeSbom, manifest),
  checkedSourceArchive(status.nativeSourceArchive, manifest, manifestFile),
];
if (new Set(releaseFiles.map((file) => path.basename(file))).size !== releaseFiles.length) {
  fail("release SBOM/source assets must have distinct filenames");
}

console.log("Release supply-chain gate passed.");
