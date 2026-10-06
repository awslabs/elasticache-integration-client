import { rm } from "node:fs/promises";

await Promise.all([
  rm("dist", { recursive: true, force: true }),
  rm("coverage", { recursive: true, force: true }),
  rm(".artifacts", { recursive: true, force: true }),
  rm("vendor/developer-toolkit-elasticache-package/dist", { recursive: true, force: true }),
  rm("vendor/developer-toolkit-elasticache-package/src", { recursive: true, force: true }),
  ...["CHANGELOG.md", "LICENSE", "NOTICE", "README.md"].map((file) =>
    rm(`vendor/developer-toolkit-elasticache-package/${file}`, { force: true }),
  ),
]);
