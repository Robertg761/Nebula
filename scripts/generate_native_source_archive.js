#!/usr/bin/env node

const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");

const repoRoot = path.resolve(__dirname, "..");
const manifestPath = path.join(repoRoot, "release", "native-source-manifest.json");
const outputPath = path.resolve(
  repoRoot,
  process.argv[2] || "dist/Nebula-native-sources.tar.gz",
);
const bundleRoot = "Nebula-native-sources";

process.env.LC_ALL = "C";
process.env.TZ = "UTC";

function fail(message) {
  console.error(`Native source archive: ${message}`);
  process.exit(1);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    stdio: "inherit",
    ...options,
  });
  if (result.error || result.status !== 0) {
    fail(`${command} failed${result.error ? `: ${result.error.message}` : ""}`);
  }
  return result;
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function checkedRelativePath(value, label) {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    path.posix.isAbsolute(value) ||
    value.split("/").some((part) => part === "" || part === "." || part === "..") ||
    value.includes("\\")
  ) {
    fail(`${label} must be a safe relative path`);
  }
  return value;
}

function checkedManifest() {
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  } catch (error) {
    fail(`cannot parse release/native-source-manifest.json: ${error.message}`);
  }
  if (manifest.schemaVersion !== 1 || !Array.isArray(manifest.sources)) {
    fail("unsupported source manifest");
  }
  if (manifest.sources.length === 0) fail("source manifest is empty");

  const names = new Set();
  const paths = new Set();
  for (const source of manifest.sources) {
    if (!source || typeof source.name !== "string" || source.name.trim() === "") {
      fail("every source needs a name");
    }
    const normalizedName = source.name.toLowerCase();
    if (names.has(normalizedName)) fail(`duplicate source name: ${source.name}`);
    names.add(normalizedName);

    const archivePath = checkedRelativePath(source.archivePath, `${source.name}.archivePath`);
    if (paths.has(archivePath)) fail(`duplicate archive path: ${archivePath}`);
    paths.add(archivePath);
    if (typeof source.license !== "string" || source.license.trim() === "") {
      fail(`${source.name} has no reviewed license`);
    }

    if (source.type === "git") {
      if (!/^https:\/\//.test(source.repository || "")) {
        fail(`${source.name} must use an HTTPS Git repository`);
      }
      if (!/^[0-9a-f]{40}$/.test(source.commit || "")) {
        fail(`${source.name} must pin a full Git commit`);
      }
    } else if (source.type === "archive") {
      if (!/^https:\/\//.test(source.url || "")) {
        fail(`${source.name} must use an HTTPS archive URL`);
      }
      if (!/^[0-9a-f]{64}$/.test(source.sha256 || "")) {
        fail(`${source.name} must pin an archive SHA-256`);
      }
      if (!Number.isInteger(source.stripComponents) || source.stripComponents < 0) {
        fail(`${source.name}.stripComponents must be a non-negative integer`);
      }
    } else {
      fail(`${source.name} has unsupported source type ${source.type}`);
    }
  }
  return manifest;
}

function validateTarListing(archive, label) {
  const listing = spawnSync("tar", ["-tzf", archive], {
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
  });
  if (listing.error || listing.status !== 0) fail(`${label} is not a readable tar.gz`);
  const entries = listing.stdout.split(/\r?\n/).filter(Boolean);
  if (entries.length === 0) fail(`${label} is empty`);
  for (const entry of entries) {
    if (
      path.posix.isAbsolute(entry) ||
      entry.includes("\\") ||
      entry.split("/").includes("..")
    ) {
      fail(`${label} contains an unsafe path: ${entry}`);
    }
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
    fail(`${label} contains unreadable metadata or links`);
  }
}

function addGitSource(source, tempRoot, stagingRoot, index) {
  const gitDir = path.join(tempRoot, `git-${index}`);
  const sourceTar = path.join(tempRoot, `git-${index}.tar`);
  fs.mkdirSync(gitDir);
  run("git", ["-C", gitDir, "init", "--quiet"]);
  run("git", ["-C", gitDir, "remote", "add", "origin", source.repository]);
  run("git", [
    "-C",
    gitDir,
    "fetch",
    "--quiet",
    "--depth",
    "1",
    "--no-tags",
    "origin",
    source.commit,
  ]);
  run("git", ["-C", gitDir, "cat-file", "-e", `${source.commit}^{commit}`]);
  run("git", [
    "-C",
    gitDir,
    "archive",
    "--format=tar",
    `--prefix=${bundleRoot}/${source.archivePath}/`,
    `--output=${sourceTar}`,
    source.commit,
  ]);
  run("tar", ["-xf", sourceTar, "-C", stagingRoot]);
}

function addArchiveSource(source, tempRoot, stagingRoot, index) {
  const downloaded = path.join(tempRoot, `archive-${index}.tar.gz`);
  run("curl", [
    "--proto",
    "=https",
    "--tlsv1.2",
    "--fail",
    "--location",
    "--retry",
    "3",
    "--connect-timeout",
    "30",
    "--max-time",
    "300",
    "--max-filesize",
    "536870912",
    "--silent",
    "--show-error",
    "--output",
    downloaded,
    source.url,
  ]);
  const actualHash = sha256(downloaded);
  if (actualHash !== source.sha256) {
    fail(`${source.name} archive checksum mismatch: ${actualHash}`);
  }
  validateTarListing(downloaded, source.name);
  const destination = path.join(stagingRoot, bundleRoot, source.archivePath);
  fs.mkdirSync(destination, { recursive: true });
  run("tar", [
    "-xzf",
    downloaded,
    "-C",
    destination,
    `--strip-components=${source.stripComponents}`,
    "--no-same-owner",
    "--no-same-permissions",
  ]);
}

const manifest = checkedManifest();
const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "nebula-native-sources-"));
const stagingRoot = path.join(tempRoot, "staging");
const tarPath = path.join(tempRoot, "native-sources.tar");
fs.mkdirSync(path.join(stagingRoot, bundleRoot), { recursive: true });

try {
  manifest.sources.forEach((source, index) => {
    console.log(`Fetching ${source.name} ${source.version}`);
    if (source.type === "git") {
      addGitSource(source, tempRoot, stagingRoot, index);
    } else {
      addArchiveSource(source, tempRoot, stagingRoot, index);
    }
  });

  fs.copyFileSync(
    manifestPath,
    path.join(stagingRoot, bundleRoot, "native-source-manifest.json"),
  );
  fs.writeFileSync(
    path.join(stagingRoot, bundleRoot, "README.txt"),
    [
      "Nebula native corresponding source",
      "",
      "The native-source-manifest.json file pins every source revision used by",
      "dev.jdtech.mpv:libmpv:0.4.1, including upstream submodules and build flags.",
      "The libmpv-android source tree contains the complete Android build recipe.",
      "",
    ].join("\n"),
    "utf8",
  );

  run("tar", [
    "--sort=name",
    "--mtime=@0",
    "--owner=0",
    "--group=0",
    "--numeric-owner",
    "--mode=u+rwX,go+rX,go-w,a-s",
    "--format=posix",
    "--pax-option=exthdr.name=%d/PaxHeaders/%f,delete=atime,delete=ctime",
    "-C",
    stagingRoot,
    "-cf",
    tarPath,
    bundleRoot,
  ]);
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  const outputFd = fs.openSync(outputPath, "w", 0o644);
  try {
    run("gzip", ["-n", "-9", "-c", tarPath], {
      stdio: ["ignore", outputFd, "inherit"],
    });
  } finally {
    fs.closeSync(outputFd);
  }
  console.log(`${path.relative(repoRoot, outputPath)} ${sha256(outputPath)}`);
} finally {
  fs.rmSync(tempRoot, { recursive: true, force: true });
}
