process.on("uncaughtException", e => { console.error("UNCAUGHT:", e.stack); process.exit(2); });
const session = require("./lib/session");
try { console.log(JSON.stringify(session.list(), null, 2)); } catch (e) { console.error("ERR:", e.stack); }
