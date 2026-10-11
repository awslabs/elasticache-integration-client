import { execFileSync } from "node:child_process";
import { cp, mkdir, rm } from "node:fs/promises";

const sourceRoot = "vendor/developer-toolkit-elasticache/node";
const packageRoot = "vendor/developer-toolkit-elasticache-package";

execFileSync("npm", ["run", "build", "--prefix", sourceRoot], {
  stdio: "inherit",
});

await rm(`${packageRoot}/dist`, { recursive: true, force: true });
await rm(`${packageRoot}/src`, { recursive: true, force: true });
await mkdir(packageRoot, { recursive: true });
await cp(`${sourceRoot}/dist`, `${packageRoot}/dist`, { recursive: true });
await cp(`${sourceRoot}/src`, `${packageRoot}/src`, { recursive: true });

for (const file of ["CHANGELOG.md", "LICENSE", "NOTICE", "README.md"]) {
  await cp(`${sourceRoot}/${file}`, `${packageRoot}/${file}`);
}
