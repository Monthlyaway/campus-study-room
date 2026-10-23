import { generateService } from "@umijs/openapi";
import { readFile, mkdtemp, writeFile, rm } from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";
const web = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const temp = await mkdtemp(path.join(os.tmpdir(), "studyroom-openapi-"));
try {
  for (const name of ["core", "repairs"]) {
    // Canonical contracts use JSON syntax, a strict subset of YAML.
    const schema = JSON.parse(
      await readFile(
        path.join(web, "../../contracts/openapi", `${name}.yaml`),
        "utf8"
      )
    );
    const schemaPath = path.join(temp, `${name}.json`);
    await writeFile(schemaPath, JSON.stringify(schema));
    await generateService({
      schemaPath,
      serversPath: path.join(web, "src/services/generated", name),
      requestLibPath: "import { request } from '../../../api';",
      namespace: name === "core" ? "CoreAPI" : "RepairAPI",
      dataFields: ["data"],
      enumStyle: "string-literal",
    });
  }
} finally {
  await rm(temp, { recursive: true, force: true });
}
