const { execSync } = require('node:child_process');
const { existsSync, rmSync, mkdirSync, copyFileSync, readdirSync, statSync } = require('node:fs');
const { resolve } = require('node:path');

function run(cmd, opts = {}) {
  console.log(`> ${cmd}`);
  execSync(cmd, { stdio: 'inherit', ...opts });
}

function findPnpm() {
  const pnpmExec = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm';
  try {
    execSync(`${pnpmExec} --version`, { stdio: 'ignore' });
    return pnpmExec;
  } catch {
    return 'npx pnpm';
  }
}

function copyRecursive(src, dest) {
  const stat = statSync(src);
  if (stat.isDirectory()) {
    if (!existsSync(dest)) mkdirSync(dest, { recursive: true });
    for (const name of readdirSync(src)) {
      copyRecursive(resolve(src, name), resolve(dest, name));
    }
  } else {
    copyFileSync(src, dest);
  }
}

function loadVisualStudioEnv() {
  const vswhere = (() => {
    try {
      execSync('vswhere -version', { stdio: 'ignore' });
      return 'vswhere';
    } catch {
      const defaultPath = 'C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe';
      if (existsSync(defaultPath)) return defaultPath;
      return null;
    }
  })();

  const applyMsvcBinPath = (installPath) => {
    const msvcRoot = resolve(installPath, 'VC', 'Tools', 'MSVC');
    if (!existsSync(msvcRoot)) return false;

    const versions = readdirSync(msvcRoot).filter((name) => {
      const full = resolve(msvcRoot, name);
      return statSync(full).isDirectory();
    });

    if (versions.length === 0) return false;

    const hostBin = resolve(msvcRoot, versions[0], 'bin', 'Hostx64', 'x64');
    if (!existsSync(hostBin)) return false;

    // Prepend to PATH so rustc can find link.exe
    process.env.PATH = `${hostBin};${process.env.PATH}`;
    return true;
  };

  if (!vswhere) {
    // Fallback: try the default VS BuildTools install location
    const fallback = 'C:\\Program Files (x86)\\Microsoft Visual Studio\\18\\BuildTools';
    if (existsSync(fallback) && applyMsvcBinPath(fallback)) {
      return true;
    }
    return false;
  }

  try {
    const installPath = execSync(
      `"${vswhere}" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`,
      { stdio: 'pipe' },
    )
      .toString('utf8')
      .trim();

    if (!installPath) return false;

    const vcvars = resolve(installPath, 'VC', 'Auxiliary', 'Build', 'vcvarsall.bat');
    if (existsSync(vcvars)) {
      // Source the environment to ensure all vars are correct
      const output = execSync(`cmd /c "call \"${vcvars}\" x64 >nul && set"`, {
        stdio: 'pipe',
        encoding: 'utf8',
      });

      output.split(/\r?\n/).forEach((line) => {
        const idx = line.indexOf('=');
        if (idx <= 0) return;
        const key = line.slice(0, idx);
        const value = line.slice(idx + 1);
        process.env[key] = value;
      });
    }

    // Always add MSVC link dir to PATH in case vswhere didn't set it
    return applyMsvcBinPath(installPath);
  } catch {
    return false;
  }
}

function main() {
  const frontendRoot = resolve(__dirname, '..');
  const workspaceRoot = resolve(frontendRoot, '..');

  // Path to the AMLL repo next to DroidMate
  const amllRoot = resolve(workspaceRoot, '..', 'AMLL', 'applemusic-like-lyrics');
  const amllPlayerDist = resolve(amllRoot, 'packages', 'player', 'dist');

  // Destination in DroidMate Android assets
  const destAssets = resolve(workspaceRoot, 'app', 'src', 'main', 'assets', 'amll');

  if (!existsSync(amllRoot)) {
    console.error(`AMLL repo not found at ${amllRoot}`);
    process.exit(1);
  }

  console.log('=== Building AMLL Player (from:', amllRoot, ')');

  const pnpm = findPnpm();

  // Ensure dependencies + build
  run(`${pnpm} install --frozen-lockfile`, { cwd: amllRoot });

  // On Windows, ensure MSVC linker is available so Rust can build native crates.
  if (process.platform === 'win32') {
    try {
      execSync('where link.exe', { stdio: 'ignore' });
    } catch {
      const loaded = loadVisualStudioEnv();
      if (!loaded) {
        console.error(
          '\nERROR: link.exe not found on PATH.\n' +
            'The AMLL build requires Rust with MSVC toolchain (Visual Studio Build Tools).\n' +
            'Please run this script from a "Developer Command Prompt" or install the "Desktop development with C++" workload.\n',
        );
        process.exit(1);
      }
    }
  }

  try {
    // When embedding the build inside Android assets, use relative URLs so resources
    // (e.g. /assets/...) are resolved correctly from file:// paths.
    process.env.VITE_BASE = process.env.VITE_BASE || "./";

    run(`${pnpm} nx build player`, { cwd: amllRoot });
  } catch (err) {
    console.error('\nBuild failed. If it is due to missing Rust toolchain (cargo/wasm-pack/link.exe), install Rust + MSVC build tools and rerun.');
    throw err;
  }

  if (!existsSync(amllPlayerDist)) {
    console.error(`Expected build output not found: ${amllPlayerDist}`);
    process.exit(1);
  }

  // Prepare destination
  if (existsSync(destAssets)) {
    console.log('Cleaning existing assets at', destAssets);
    rmSync(destAssets, { recursive: true, force: true });
  }
  mkdirSync(destAssets, { recursive: true });

  console.log('Copying build output to', destAssets);
  copyRecursive(amllPlayerDist, destAssets);

  console.log('✅ AMLL Player build copied to DroidMate assets');
}

main();
