#!/usr/bin/env node

const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");

const repoRoot = path.resolve(__dirname, "..");
const androidRoot = path.join(repoRoot, "apps", "android-tv-host");
const lockPath = path.join(androidRoot, "app", "gradle.lockfile");
const verificationPath = path.join(androidRoot, "gradle", "verification-metadata.xml");
const manifestPath = path.join(repoRoot, "release", "native-source-manifest.json");
const gradleOutput = path.join(repoRoot, "release", "gradle-sbom.cdx.json");
const nativeOutput = path.join(repoRoot, "release", "native-sbom.cdx.json");

function fail(message) {
  console.error(`Release SBOM: ${message}`);
  process.exit(1);
}

function sha256Buffer(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function sha256File(file) {
  return sha256Buffer(fs.readFileSync(file));
}

const licenseOverrides = new Map([
  ["com.google.guava:listenablefuture:1.0", [{ id: "Apache-2.0" }]],
  ["com.google.zxing:core:3.5.3", [{ id: "Apache-2.0" }]],
  ["org.nanohttpd:nanohttpd:2.3.1", [{ id: "BSD-3-Clause" }]],
]);

const knownLicenseIds = new Map([
  ["apache license, version 2.0", "Apache-2.0"],
  ["apache 2", "Apache-2.0"],
  ["apache-2.0", "Apache-2.0"],
  ["the apache software license, version 2.0", "Apache-2.0"],
  ["mit", "MIT"],
  ["mit license", "MIT"],
  ["the mit license", "MIT"],
  ["bsd", "BSD-3-Clause"],
  ["bsd 3-clause", "BSD-3-Clause"],
  ["bsd-3-clause", "BSD-3-Clause"],
]);

function decodeXml(value) {
  return value
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&")
    .trim();
}

function cachedFiles(group, name, version) {
  const base = path.join(
    os.homedir(),
    ".gradle",
    "caches",
    "modules-2",
    "files-2.1",
    group,
    name,
    version,
  );
  if (!fs.existsSync(base)) fail(`Gradle cache is missing ${group}:${name}:${version}`);
  const files = [];
  for (const digestDirectory of fs.readdirSync(base).sort()) {
    const directory = path.join(base, digestDirectory);
    if (!fs.statSync(directory).isDirectory()) continue;
    for (const fileName of fs.readdirSync(directory).sort()) {
      const file = path.join(directory, fileName);
      if (fs.statSync(file).isFile()) files.push(file);
    }
  }
  return files;
}

function declaredLicenses(coordinate, pomFile) {
  const override = licenseOverrides.get(coordinate);
  if (override) return override.map((license) => ({ license }));

  const xml = fs.readFileSync(pomFile, "utf8");
  const section = xml.match(/<licenses>([\s\S]*?)<\/licenses>/)?.[1] || "";
  const records = [...section.matchAll(/<license>([\s\S]*?)<\/license>/g)].map((match) => {
    const name = decodeXml(match[1].match(/<name>([\s\S]*?)<\/name>/)?.[1] || "");
    const url = decodeXml(match[1].match(/<url>([\s\S]*?)<\/url>/)?.[1] || "");
    const id = knownLicenseIds.get(name.toLowerCase());
    return {
      license: {
        ...(id ? { id } : { name }),
        ...(url ? { url } : {}),
      },
    };
  }).filter(({ license }) => license.id || license.name);
  if (records.length === 0) fail(`no reviewed license declaration for ${coordinate}`);
  return records;
}

function writeJson(file, value) {
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function appVersion() {
  const buildFile = fs.readFileSync(path.join(androidRoot, "app", "build.gradle.kts"), "utf8");
  const match = buildFile.match(/\bversionName\s*=\s*"([^"]+)"/);
  if (!match) fail("cannot read app versionName");
  return match[1];
}

function verificationHashes(xml, group, name, version) {
  const marker = `<component group="${group}" name="${name}" version="${version}">`;
  const start = xml.indexOf(marker);
  if (start < 0) fail(`verification metadata is missing ${group}:${name}:${version}`);
  const end = xml.indexOf("</component>", start);
  if (end < 0) fail(`verification metadata is malformed for ${group}:${name}:${version}`);
  const hashes = [...xml.slice(start, end).matchAll(/<sha256 value="([0-9a-f]{64})"/g)]
    .map((match) => match[1]);
  if (hashes.length === 0) fail(`verification metadata has no SHA-256 for ${group}:${name}:${version}`);
  return [...new Set(hashes)].sort();
}

function selectVerifiedArtifacts(files, hashes, coordinate, name, version) {
  const verified = files
    .filter((file) => !file.endsWith(".pom") && !file.endsWith(".module"))
    .map((file) => ({ file, digest: sha256File(file) }))
    .filter(({ digest }) => hashes.includes(digest));
  if (verified.length <= 1) return verified;

  const canonical = verified.filter(({ file }) => {
    const fileName = path.basename(file);
    return [".aar", ".jar"].some(
      (extension) => fileName === `${name}-${version}${extension}`,
    );
  });
  if (canonical.length === 1) return canonical;
  fail(`ambiguous verified artifacts for ${coordinate}`);
}

function generateGradleSbom(version) {
  const verification = fs.readFileSync(verificationPath, "utf8");
  const components = fs.readFileSync(lockPath, "utf8")
    .split(/\r?\n/)
    .filter((line) => line && !line.startsWith("#") && line.includes("="))
    .map((line) => {
      const separator = line.indexOf("=");
      const coordinate = line.slice(0, separator);
      const configurations = line.slice(separator + 1).split(",");
      return { coordinate, configurations };
    })
    .filter(({ configurations }) => configurations.includes("releaseRuntimeClasspath"))
    .map(({ coordinate }) => {
      const parts = coordinate.split(":");
      if (parts.length !== 3 || parts.some((part) => part.length === 0)) {
        fail(`unsupported locked coordinate: ${coordinate}`);
      }
      const [group, name, componentVersion] = parts;
      const hashes = verificationHashes(verification, group, name, componentVersion);
      const files = cachedFiles(group, name, componentVersion);
      const pomFiles = files.filter((file) => file.endsWith(".pom"));
      if (pomFiles.length !== 1) {
        fail(`expected one cached POM for ${coordinate}, found ${pomFiles.length}`);
      }
      const cachedArtifacts = selectVerifiedArtifacts(
        files,
        hashes,
        coordinate,
        name,
        componentVersion,
      );
      return {
        type: "library",
        group,
        name,
        version: componentVersion,
        "bom-ref": `pkg:maven/${encodeURIComponent(group)}/${encodeURIComponent(name)}@${encodeURIComponent(componentVersion)}`,
        purl: `pkg:maven/${encodeURIComponent(group)}/${encodeURIComponent(name)}@${encodeURIComponent(componentVersion)}`,
        licenses: declaredLicenses(coordinate, pomFiles[0]),
        ...(cachedArtifacts.length === 1
          ? { hashes: [{ alg: "SHA-256", content: cachedArtifacts[0].digest }] }
          : {}),
        properties: [
          { name: "nebula:gradle-configuration", value: "releaseRuntimeClasspath" },
          { name: "nebula:verification-sha256", value: hashes.join(",") },
          { name: "nebula:pom-sha256", value: sha256File(pomFiles[0]) },
          {
            name: "nebula:pom-verification-covered",
            value: String(hashes.includes(sha256File(pomFiles[0]))),
          },
          ...cachedArtifacts.map(({ file, digest }) => ({
            name: "nebula:artifact-sha256",
            value: `${path.basename(file)}=${digest}`,
          })),
        ],
      };
    })
    .sort((left, right) => left["bom-ref"].localeCompare(right["bom-ref"]));
  if (components.length === 0) fail("releaseRuntimeClasspath is empty");

  writeJson(gradleOutput, {
    bomFormat: "CycloneDX",
    specVersion: "1.6",
    version: 1,
    metadata: {
      component: {
        type: "application",
        group: "com.stremioshell.host",
        name: "Nebula",
        version,
      },
      properties: [
        { name: "nebula:source-lock", value: "apps/android-tv-host/app/gradle.lockfile" },
        {
          name: "nebula:verification-metadata",
          value: "apps/android-tv-host/gradle/verification-metadata.xml",
        },
      ],
    },
    components,
  });
}

function locateAar(manifest) {
  const explicit = process.argv[2] ? path.resolve(process.argv[2]) : null;
  if (explicit) {
    if (!fs.existsSync(explicit) || !fs.statSync(explicit).isFile()) {
      fail(`AAR does not exist: ${explicit}`);
    }
    return explicit;
  }
  const base = path.join(
    os.homedir(),
    ".gradle",
    "caches",
    "modules-2",
    "files-2.1",
    "dev.jdtech.mpv",
    "libmpv",
    manifest.bundle.version,
  );
  if (!fs.existsSync(base)) fail("libmpv AAR is not in the Gradle cache; pass its path explicitly");
  const candidates = [];
  for (const digestDirectory of fs.readdirSync(base)) {
    const candidate = path.join(base, digestDirectory, `libmpv-${manifest.bundle.version}.aar`);
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) candidates.push(candidate);
  }
  if (candidates.length !== 1) fail(`expected one cached libmpv AAR, found ${candidates.length}`);
  return candidates[0];
}

function unzipEntries(aar) {
  const result = spawnSync("unzip", ["-Z1", aar], {
    encoding: "utf8",
    maxBuffer: 4 * 1024 * 1024,
  });
  if (result.error || result.status !== 0) fail("cannot list libmpv AAR");
  return result.stdout.split(/\r?\n/).filter((entry) => /^jni\/[^/]+\/[^/]+\.so$/.test(entry));
}

function unzipBuffer(aar, entry) {
  const result = spawnSync("unzip", ["-p", aar, entry], {
    encoding: null,
    maxBuffer: 32 * 1024 * 1024,
  });
  if (result.error || result.status !== 0) fail(`cannot read ${entry} from libmpv AAR`);
  return result.stdout;
}

function binaryIdentity(fileName, manifest) {
  if (fileName === "libmpv.so") {
    return { version: manifest.build.mpv.version, license: "GPL-2.0-or-later", source: "mpv" };
  }
  if (fileName === "libplayer.so") {
    return { version: manifest.bundle.version, license: "MIT", source: "libmpv-android" };
  }
  if (fileName === "libc++_shared.so") {
    return {
      version: manifest.bundle.androidNdkVersion,
      license: "Apache-2.0 WITH LLVM-exception",
      source: "Android NDK libc++",
    };
  }
  return {
    version: manifest.build.ffmpeg.version,
    license: "GPL-3.0-or-later",
    source: "FFmpeg",
  };
}

function sourceComponent(source) {
  const reference = source.type === "git"
    ? `${source.repository}#${source.commit}`
    : source.url;
  return {
    type: "library",
    name: source.name,
    version: source.version,
    "bom-ref": `source:${source.archivePath}`,
    licenses: [{ expression: source.license }],
    externalReferences: [
      {
        type: source.type === "git" ? "vcs" : "distribution",
        url: reference,
        ...(source.type === "archive"
          ? { hashes: [{ alg: "SHA-256", content: source.sha256 }] }
          : {}),
      },
    ],
    properties: [
      { name: "nebula:corresponding-source-path", value: source.archivePath },
    ],
  };
}

function generateNativeSbom(version, manifest, aar) {
  const actualAarHash = sha256File(aar);
  if (actualAarHash !== manifest.bundle.aarSha256) {
    fail(`libmpv AAR checksum mismatch: ${actualAarHash}`);
  }
  const binaries = unzipEntries(aar).sort().map((entry) => {
    const [, abi, fileName] = entry.split("/");
    const identity = binaryIdentity(fileName, manifest);
    const binary = unzipBuffer(aar, entry);
    return {
      type: "file",
      name: fileName,
      version: identity.version,
      "bom-ref": `native:${abi}/${fileName}`,
      hashes: [{ alg: "SHA-256", content: sha256Buffer(binary) }],
      licenses: [{ expression: identity.license }],
      properties: [
        { name: "nebula:android-abi", value: abi },
        { name: "nebula:aar-entry", value: entry },
        { name: "nebula:source-component", value: identity.source },
        { name: "nebula:size-bytes", value: String(binary.length) },
      ],
    };
  });

  writeJson(nativeOutput, {
    bomFormat: "CycloneDX",
    specVersion: "1.6",
    version: 1,
    metadata: {
      component: {
        type: "library",
        group: "dev.jdtech.mpv",
        name: "libmpv",
        version: manifest.bundle.version,
        hashes: [{ alg: "SHA-256", content: manifest.bundle.aarSha256 }],
        licenses: [{ expression: manifest.bundle.effectiveLicense }],
        purl: `pkg:maven/dev.jdtech.mpv/libmpv@${manifest.bundle.version}`,
      },
      properties: [
        { name: "nebula:consuming-application", value: `Nebula ${version}` },
        { name: "nebula:native-source-manifest", value: "release/native-source-manifest.json" },
        { name: "nebula:ffmpeg-build-flags", value: manifest.build.ffmpeg.flags.join(" ") },
        { name: "nebula:mpv-build-flags", value: manifest.build.mpv.flags.join(" ") },
        {
          name: "nebula:mpv-effective-gpl",
          value: String(manifest.build.mpv.effectiveOptions?.gpl === true),
        },
      ],
    },
    components: [
      ...manifest.sources.map(sourceComponent),
      ...binaries,
    ],
  });
}

let manifest;
try {
  manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
} catch (error) {
  fail(`cannot parse native source manifest: ${error.message}`);
}
if (manifest.schemaVersion !== 1 || !Array.isArray(manifest.sources)) {
  fail("unsupported native source manifest");
}
const version = appVersion();
const aar = locateAar(manifest);
generateGradleSbom(version);
generateNativeSbom(version, manifest, aar);
console.log(`Generated ${path.relative(repoRoot, gradleOutput)}`);
console.log(`Generated ${path.relative(repoRoot, nativeOutput)}`);
