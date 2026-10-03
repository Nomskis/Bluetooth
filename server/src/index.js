import { loadConfig } from './config.js';
import { createEarshotServer } from './server.js';

const config = loadConfig();
const server = createEarshotServer(config);
const address = await server.listen();

const turn = config.ice.turnUrls.length > 0 ? `TURN: ${config.ice.turnUrls.join(', ')}` : 'TURN: not configured';
console.log(`Earshot server listening on http://${address.address}:${address.port}`);
console.log(`Serving web client from ${config.webRoot}`);
console.log(turn);

let shuttingDown = false;
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, async () => {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`Received ${signal}, shutting down`);
    await server.close();
    process.exit(0);
  });
}
