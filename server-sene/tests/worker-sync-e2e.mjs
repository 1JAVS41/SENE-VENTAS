import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import worker from "../worker.js";
import { readFileSync } from "node:fs";

class D1Statement {
  constructor(db, sql) { this.db = db; this.sql = sql; this.args = []; }
  bind(...args) { this.args = args; return this; }
  run() {
    const result = this.db.prepare(this.sql).run(...this.args);
    return { meta: { changes: Number(result.changes), last_row_id: Number(result.lastInsertRowid ?? 0) } };
  }
  first() { return this.db.prepare(this.sql).get(...this.args) ?? null; }
  all() { return { results: this.db.prepare(this.sql).all(...this.args) }; }
}
class D1Mock {
  constructor(db) { this.db = db; }
  prepare(sql) { return new D1Statement(this.db, sql); }
  batch(statements) {
    const run = this.db.transaction(() => statements.map(statement => statement.run()));
    return Promise.resolve(run());
  }
}
const db = new DatabaseSync(":memory:");
db.exec(`
CREATE TABLE businesses (
 id TEXT PRIMARY KEY, business_name TEXT NOT NULL, business_name_normalized TEXT NOT NULL UNIQUE,
 holder_name TEXT, whatsapp TEXT, installation_id TEXT UNIQUE, status TEXT NOT NULL,
 activated_at TEXT, expires_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);`);
db.exec(readFileSync(new URL("../migrations/0001_complete_sync.sql", import.meta.url), "utf8"));
db.exec(readFileSync(new URL("../migrations/0002_retention.sql", import.meta.url), "utf8"));
const d1 = new D1Mock(db);
const now = new Date().toISOString();
db.prepare(`INSERT INTO businesses (id,business_name,business_name_normalized,installation_id,status,expires_at,created_at,updated_at)
 VALUES (?,?,?,?,?,?,?,?)`).run("biz-1","Negocio de prueba","negocio de prueba","installation-sales-00000001","active",
 new Date(Date.now()+30*86400000).toISOString(),now,now);

async function post(path, body) {
 const response = await worker.fetch(new Request("https://sene.test"+path, {
  method:"POST", headers:{"content-type":"application/json"}, body:JSON.stringify(body)
 }), { DB:d1 });
 return { status:response.status, body:await response.json() };
}
const created = await post("/admin/pairing/create", { installation_id:"installation-sales-00000001" });
assert.equal(created.status,201);
assert.match(created.body.pairing_code,/^[A-HJ-NP-Z2-9]{10}$/);

const pair = await crypto.subtle.generateKey({
 name:"RSA-OAEP", modulusLength:2048, publicExponent:new Uint8Array([1,0,1]), hash:"SHA-256"
}, true, ["encrypt","decrypt"]);
const spki = new Uint8Array(await crypto.subtle.exportKey("spki",pair.publicKey));
const publicKey = Buffer.from(spki).toString("base64url");
const claimed = await post("/admin/pairing/claim", {
 pairing_code:created.body.pairing_code, admin_installation_id:"installation-admin-00000001", rp:publicKey
});
assert.equal(claimed.status,200);
assert.ok(claimed.body.admin_token);
assert.equal(claimed.body.business_id,"biz-1");
const replay = await post("/admin/pairing/claim", {
 pairing_code:created.body.pairing_code, admin_installation_id:"installation-admin-00000001", rp:publicKey
});
assert.equal(replay.status,409);

const eventTime = Date.now();
const salesEvents = {
 v:1,t:"[SYNC]",i:"installation-sales-00000001",c:"",
 r:[{v:1,t:"[P$]",e:"loan-1",w:"worker-1",a:12500,n:"Ana",z:"Prueba de préstamo",i:eventTime}],
 h:[{v:1,t:"[AT_SYNC]",e:"attendance-1",w:"worker-1",d:"2026-11-22",x:"[EC]",s:eventTime}],
 g:[{v:1,t:"[IN_SYNC]",e:"inventory-1",g:"S",i:eventTime,f:true,p:[{c:"SKU-1",n:"Producto",a:2500,s:8,l:"A",h:"1"}]}],
 b:[{v:1,t:"[SYNC]",e:"report-1",w:"worker-1",k:"[SYNC]",i:eventTime,b:"SENE-ADMIN-REPORT|2\\nTEST-ENCRYPTED-ENVELOPE"}]
};
const salesSync = await post("/payroll/requests/sync",salesEvents);
assert.equal(salesSync.status,200);
assert.deepEqual(salesSync.body.ha,["attendance-1"]);
assert.deepEqual(salesSync.body.ga,["inventory-1"]);
assert.deepEqual(salesSync.body.ba,["report-1"]);
assert.equal(salesSync.body.rp,publicKey);

const adminSync = await post("/admin/payroll/requests/sync", {
 v:1,t:"[SYNC]",a:claimed.body.admin_token,c:"",d:[],ha:[],gi:[],ba:[],rp:publicKey
});
assert.equal(adminSync.status,200);
assert.equal(adminSync.body.items.length,1);
assert.equal(adminSync.body.items[0].e,"loan-1");
assert.equal(adminSync.body.h[0].e,"attendance-1");
assert.equal(adminSync.body.g[0].e,"inventory-1");
assert.equal(adminSync.body.g[0].f,true);
assert.equal(adminSync.body.b[0].e,"report-1");
assert.equal(adminSync.body.b[0].b,"SENE-ADMIN-REPORT|2\\nTEST-ENCRYPTED-ENVELOPE");

const adminAck = await post("/admin/payroll/requests/sync", {
 v:1,t:"[SYNC]",a:claimed.body.admin_token,c:"",d:[{e:"loan-1",x:"[OK]",r:"Autorizado"}],
 ha:["attendance-1"],gi:["inventory-1"],ba:["report-1"],rp:publicKey
});
assert.equal(adminAck.status,200);
assert.deepEqual(adminAck.body.haa,["attendance-1"]);
assert.deepEqual(adminAck.body.gaa,["inventory-1"]);
assert.deepEqual(adminAck.body.baa,["report-1"]);
assert.equal(adminAck.body.items.find(x=>x.e==="loan-1").x,"APPROVED");

const salesDecision = await post("/payroll/requests/sync", {
 v:1,t:"[SYNC]",i:"installation-sales-00000001",c:"",r:[],h:[],g:[],b:[]
});
assert.equal(salesDecision.status,200);
assert.equal(salesDecision.body.d[0].x,"APPROVED");
assert.equal(salesDecision.body.d[0].rr,"Autorizado");

for (let i=0;i<12;i++) {
 const stamp = new Date(Date.now()-((12-i)*1000)).toISOString();
 db.prepare("INSERT INTO inventory_events (event_id,business_id,worker_id,payload,created_at,delivered_at) VALUES (?,?,?,?,?,?)")
  .run("old-inv-"+i,"biz-1","worker-1",JSON.stringify({g:"S",e:"old-inv-"+i}),stamp,stamp);
 db.prepare("INSERT INTO report_events (event_id,business_id,worker_id,envelope,created_at,delivered_at) VALUES (?,?,?,?,?,?)")
  .run("old-report-"+i,"biz-1","worker-1",JSON.stringify({k:"[SYNC]",e:"old-report-"+i}),stamp,stamp);
}
db.prepare("INSERT INTO inventory_events (event_id,business_id,payload,created_at) VALUES (?,?,?,?)")
 .run("pending-inv","biz-1",JSON.stringify({g:"S",e:"pending-inv"}),now);
db.prepare("UPDATE server_sync_state SET updated_at=? WHERE business_id='__SYSTEM__' AND channel='retention'")
 .run(new Date(Date.now()-31*86400000).toISOString());
const tasks=[];
worker.scheduled({}, {DB:d1}, {waitUntil(p){tasks.push(p);}});
await Promise.all(tasks);
const keptInventory = db.prepare("SELECT COUNT(*) AS n FROM inventory_events WHERE business_id='biz-1' AND delivered_at IS NOT NULL").get().n;
const keptReports = db.prepare("SELECT COUNT(*) AS n FROM report_events WHERE business_id='biz-1' AND delivered_at IS NOT NULL").get().n;
const pendingInventory = db.prepare("SELECT COUNT(*) AS n FROM inventory_events WHERE business_id='biz-1' AND delivered_at IS NULL").get().n;
assert.equal(keptInventory,10);
assert.equal(keptReports,10);
assert.equal(pendingInventory,1);
assert.equal(db.prepare("SELECT status FROM payroll_requests WHERE event_id='loan-1'").get().status,"APPROVED");
console.log("SERVER-SENE sync E2E OK: one-time pairing, RSA public-key exchange, compact payroll/attendance/inventory/report transport, ACK round-trip, decisions, and safe 10-item retention.");
