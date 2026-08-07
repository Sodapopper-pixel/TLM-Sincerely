import { resolve } from "node:path";
import { createHarnessServer } from "./server.js";

async function main(): Promise<void> {
  const dataDir = resolve(process.cwd(), "data");
  const skillsDir = resolve(process.cwd(), "fixtures/skills");
  const recordingsDir = resolve(process.cwd(), "data/recordings");
  const host = process.env.SERVER_HOST ?? "127.0.0.1";
  const port = Number(process.env.SERVER_PORT ?? 7421);

  const harness = createHarnessServer({ dataDir, skillsDir, recordingsDir, host, port });
  await harness.start();
  console.log(`[harness] server listening on http://${host}:${port}`);
  console.log(`[harness] LLM_TRANSPORT=${process.env.LLM_TRANSPORT ?? "live"}`);
  console.log(`[harness] ready - create a session via POST /api/sessions`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
