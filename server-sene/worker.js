/**
 * SERVER-SENE Worker API
 * Cloudflare Worker + D1 binding DB.
 * Secrets: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID.
 * Public settings: amount_cents in app_settings.
 */
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Content-Type": "application/json; charset=utf-8"
};
const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: cors });
const nowIso = () => new Date().toISOString();
const plusHours = (date, hours) => new Date(date.getTime() + hours * 3600000).toISOString();
const normalizeName = value => value.normalize("NFKD").replace(/[\u0300-\u036f]/g, "").trim().replace(/\s+/g, " ").toLowerCase();
const randomCode = () => {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = new Uint8Array(10);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, b => alphabet[b % alphabet.length]).join("");
};
const sha256 = async value => {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
};
const validText = (v, min, max) => typeof v === "string" && v.trim().length >= min && v.trim().length <= max;

// Precios vigentes de SENE VENTAS, expresados en centavos MXN.
const PRICE_CENTS = Object.freeze({ activation: 29900, renewal: 10000 });

async function telegram(env, message) {
  const chatId = env.ID_DE_CHAT_DE_TELEGRAM || env.TELEGRAM_CHAT_ID;\n  if (!env.TELEGRAM_BOT_TOKEN || !chatId) throw new Error("Telegram secrets missing");
  const response = await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chat_id: chatId, text: message })
  });
  if (!response.ok) throw new Error("Telegram notification failed");
  const result = await response.json();
  if (!result.ok) throw new Error("Telegram notification rejected");
}

async function getAmount(type) {
  const amount = PRICE_CENTS[type];
  if (!Number.isSafeInteger(amount) || amount < 1) throw new Error("TIPO_DE_PAGO_INVALIDO");
  return amount;
}

async function createRequest(request, env) {
  const body = await request.json();
  const type = body.type;
  const holder = typeof body.holder_name === "string" ? body.holder_name.trim() : "";
  const phone = typeof body.whatsapp === "string" ? body.whatsapp.replace(/\D/g, "") : "";
  const businessName = typeof body.business_name === "string" ? body.business_name.trim() : "";
  const installationId = typeof body.installation_id === "string" ? body.installation_id.trim() : "";
  if (!["activation", "renewal"].includes(type) ||
      !validText(holder, 2, 100) || phone.length < 10 || phone.length > 15 ||
      !validText(businessName, 2, 80) || !validText(installationId, 20, 100)) {
    return json({ ok: false, error: "DATOS_INVALIDOS" }, 400);
  }

  const db = env.DB;
  const normalized = normalizeName(businessName);
  let business = await db.prepare(
    "SELECT * FROM businesses WHERE business_name_normalized=?"
  ).bind(normalized).first();

  if (business && business.status === "blocked") {
    return json({ ok: false, error: "NEGOCIO_BLOQUEADO",
      message: "Este negocio está bloqueado. Contacta al responsable de SENE." }, 403);
  }

  if (type === "activation" && !business) {
    const linked = await db.prepare(
      "SELECT business_name FROM businesses WHERE installation_id=? LIMIT 1"
    ).bind(installationId).first();
    if (linked) {
      return json({ ok: false, error: "DISPOSITIVO_YA_VINCULADO",
        message: "Este dispositivo ya está vinculado al negocio: " + linked.business_name + ". Para renovar, usa PAGAR MENSUALIDAD." }, 409);
    }
  }

  if (type === "activation" && business && business.installation_id !== installationId) {
    return json({ ok: false, error: "NOMBRE_EN_USO", message: "NOMBRE EN USO. ELIGE OTRO NOMBRE." }, 409);
  }
  if (type === "activation" && business && business.status === "active") {
    return json({ ok: false, error: "NEGOCIO_YA_ACTIVO", message: "Este negocio ya está registrado. Elige PAGAR MENSUALIDAD." }, 409);
  }
  if (type === "renewal" && !business) {
    return json({ ok: false, error: "NEGOCIO_NO_ENCONTRADO", message: "No encontramos ese negocio. Revisa el nombre registrado." }, 404);
  }
  if (type === "renewal" && business.installation_id && business.installation_id !== installationId) {
    return json({ ok: false, error: "DISPOSITIVO_NO_AUTORIZADO", message: "Este negocio está vinculado a otro dispositivo." }, 403);
  }
  if (type === "renewal" && business.status === "blocked") {
    return json({ ok: false, error: "NEGOCIO_BLOQUEADO" }, 403);
  }

  const pending = await db.prepare(
    "SELECT r.id,r.folio,r.expires_at,r.amount_cents FROM activation_requests r JOIN businesses b ON b.id=r.business_id WHERE b.installation_id=? AND r.status='pending' ORDER BY r.created_at DESC LIMIT 1"
  ).bind(installationId).first();
  if (pending && new Date(pending.expires_at).getTime() > Date.now()) {
    return json({ ok: false, error: "SOLICITUD_ACTIVA", folio: pending.folio, amount_cents: pending.amount_cents, expires_at: pending.expires_at }, 409);
  }
  if (pending) {
    await db.batch([
      db.prepare("UPDATE activation_requests SET status='expired' WHERE id=? AND status='pending'").bind(pending.id),
      db.prepare("UPDATE activation_codes SET invalidated_at=? WHERE request_id=? AND consumed_at IS NULL AND invalidated_at IS NULL").bind(nowIso(), pending.id)
    ]);
  }

  const amount = await getAmount(type);
  const requestId = crypto.randomUUID();
  const folio = "SENE-" + new Date().toISOString().slice(0, 10).replace(/-/g, "") + "-" + crypto.randomUUID().slice(0, 6).toUpperCase();
  const code = randomCode();
  const codeHash = await sha256(code);
  const created = new Date();
  const createdAt = created.toISOString();
  const expiresAt = plusHours(created, 48);
  const businessId = business?.id || crypto.randomUUID();

  const statements = [];
  if (!business) {
    statements.push(db.prepare(
      "INSERT INTO businesses (id,business_name,business_name_normalized,holder_name,whatsapp,installation_id,status,created_at,updated_at) VALUES (?,?,?,?,?,?, 'pending',?,?)"
    ).bind(businessId, businessName, normalized, holder, phone, installationId, createdAt, createdAt));
  } else {
    statements.push(db.prepare(
      "UPDATE businesses SET holder_name=?,whatsapp=?,updated_at=? WHERE id=?"
    ).bind(holder, phone, createdAt, businessId));
  }
  statements.push(db.prepare(
    "INSERT INTO activation_requests (id,folio,business_id,request_type,status,amount_cents,created_at,expires_at) VALUES (?,?,?,?,'pending',?,?,?)"
  ).bind(requestId, folio, businessId, type, amount, createdAt, expiresAt));
  statements.push(db.prepare(
    "INSERT INTO activation_codes (id,request_id,code_hash,created_at) VALUES (?,?,?,?)"
  ).bind(crypto.randomUUID(), requestId, codeHash, createdAt));
  statements.push(db.prepare(
    "INSERT INTO payments (id,request_id,amount_cents,verification_status) VALUES (?,?,?,'pending')"
  ).bind(crypto.randomUUID(), requestId, amount));
  statements.push(db.prepare(
    "INSERT INTO notifications (id,request_id,notification_type,status,created_at) VALUES (?,?, 'new_request','pending',?)"
  ).bind(crypto.randomUUID(), requestId, createdAt));

  try {
    await db.batch(statements);
  } catch (error) {
    if (String(error).includes("UNIQUE")) {
      return json({ ok: false, error: "NOMBRE_EN_USO", message: "NOMBRE EN USO. ELIGE OTRO NOMBRE." }, 409);
    }
    throw error;
  }

  const pesos = (amount / 100).toFixed(2);
  const message = [
    "📩 NUEVA SOLICITUD SERVER-SENE",
    "Folio: " + folio,
    "Tipo: " + (type === "activation" ? "ACTIVACIÓN" : "RENOVACIÓN"),
    "Negocio: " + businessName,
    "Titular: " + holder,
    "WhatsApp: " + phone,
    "Importe: $" + pesos + " MXN",
    "Código (entregar SOLO después de verificar el pago): " + code
  ].join("\n");

  try {
    await telegram(env, message);
    await db.prepare("UPDATE notifications SET status='sent',sent_at=? WHERE request_id=?")
      .bind(nowIso(), requestId).run();
  } catch (error) {
    await db.batch([
      db.prepare("UPDATE notifications SET status='failed' WHERE request_id=?").bind(requestId),
      db.prepare("UPDATE activation_requests SET status='cancelled' WHERE id=? AND status='pending'").bind(requestId),
      db.prepare("UPDATE activation_codes SET invalidated_at=? WHERE request_id=? AND consumed_at IS NULL").bind(nowIso(), requestId)
    ]);
    return json({ ok: false, error: "NOTIFICACION_TELEGRAM_FALLO", message: "No se pudo avisar al responsable por Telegram. Puedes volver a enviar la solicitud.", folio }, 503);
  }

  return json({
    ok: true,
    folio,
    amount_cents: amount,
    currency: "MXN",
    request_expires_at: expiresAt,
    request_type: type
  }, 201);
}

async function redeemCode(request, env) {
  const body = await request.json();
  const folio = typeof body.folio === "string" ? body.folio.trim() : "";
  const code = typeof body.code === "string" ? body.code.trim().toUpperCase() : "";
  const installationId = typeof body.installation_id === "string" ? body.installation_id.trim() : "";
  if (!validText(folio, 8, 50) || !/^[A-HJ-NP-Z2-9]{10}$/.test(code) ||
      !validText(installationId, 20, 100)) {
    return json({ ok: false, error: "DATOS_INVALIDOS" }, 400);
  }

  const db = env.DB;
  const row = await db.prepare(`
    SELECT ac.id AS code_id, ac.code_hash, ac.consumed_at, ac.invalidated_at,
           ac.attempt_count, ac.locked_at, ar.id AS request_id, ar.business_id,
           ar.request_type, ar.status AS request_status, ar.expires_at AS request_expires, b.installation_id,
           b.status AS business_status, b.expires_at AS business_expires
    FROM activation_requests ar
    JOIN activation_codes ac ON ac.request_id=ar.id
    JOIN businesses b ON b.id=ar.business_id
    WHERE ar.folio=?
  `).bind(folio).first();

  if (!row) return json({ ok: false, error: "FOLIO_NO_ENCONTRADO" }, 404);
  if (row.request_status !== "pending") return json({ ok: false, error: "SOLICITUD_NO_DISPONIBLE" }, 409);
  if (row.business_status === "blocked") {
    return json({ ok: false, error: "NEGOCIO_BLOQUEADO" }, 403);
  }
  if (!row.request_expires || new Date(row.request_expires).getTime() <= Date.now()) {
    const expiredAt = nowIso();
    await db.batch([
      db.prepare("UPDATE activation_requests SET status='expired' WHERE id=? AND status='pending'").bind(row.request_id),
      db.prepare("UPDATE activation_codes SET invalidated_at=? WHERE id=? AND consumed_at IS NULL AND invalidated_at IS NULL").bind(expiredAt, row.code_id)
    ]);
    return json({ ok: false, error: "SOLICITUD_EXPIRADA" }, 410);
  }
  if (row.consumed_at) return json({ ok: false, error: "CODIGO_YA_UTILIZADO" }, 409);
  if (row.invalidated_at) return json({ ok: false, error: "CODIGO_INVALIDADO" }, 409);
  if (row.locked_at || row.attempt_count >= 5) return json({ ok: false, error: "CODIGO_BLOQUEADO" }, 429);
  if (row.installation_id !== installationId) return json({ ok: false, error: "DISPOSITIVO_NO_AUTORIZADO" }, 403);

  const suppliedHash = await sha256(code);
  if (suppliedHash !== row.code_hash) {
    await db.prepare("UPDATE activation_codes SET attempt_count=attempt_count+1, locked_at=CASE WHEN attempt_count+1>=5 THEN ? ELSE locked_at END WHERE id=?")
      .bind(nowIso(), row.code_id).run();
    return json({ ok: false, error: "CODIGO_INCORRECTO" }, 401);
  }

  const now = new Date();
  const currentExpiry = row.business_expires ? new Date(row.business_expires) : null;
  const starts = row.request_type === "renewal" && currentExpiry && currentExpiry > now ? currentExpiry : now;
  const expires = new Date(starts.getTime() + 30 * 24 * 60 * 60 * 1000);
  const activatedAt = now.toISOString();
  const expiresAt = expires.toISOString();

  try {
    const results = await db.batch([
      db.prepare("UPDATE activation_codes SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND invalidated_at IS NULL AND locked_at IS NULL")
        .bind(activatedAt, row.code_id),
      db.prepare("UPDATE activation_requests SET status='completed' WHERE id=? AND EXISTS (SELECT 1 FROM activation_codes WHERE id=? AND consumed_at=?)")
        .bind(row.request_id, row.code_id, activatedAt),
      db.prepare("UPDATE payments SET verification_status='verified',verified_at=? WHERE request_id=? AND EXISTS (SELECT 1 FROM activation_codes WHERE id=? AND consumed_at=?)")
        .bind(activatedAt, row.request_id, row.code_id, activatedAt),
      db.prepare("UPDATE businesses SET status='active',activated_at=COALESCE(activated_at,?),expires_at=?,updated_at=? WHERE id=? AND EXISTS (SELECT 1 FROM activation_codes WHERE id=? AND consumed_at=?)")
        .bind(activatedAt, expiresAt, activatedAt, row.business_id, row.code_id, activatedAt),
      db.prepare("INSERT INTO subscriptions (id,business_id,request_id,starts_at,expires_at,created_at) SELECT ?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM activation_codes WHERE id=? AND consumed_at=?)")
        .bind(crypto.randomUUID(), row.business_id, row.request_id, starts.toISOString(), expiresAt, activatedAt, row.code_id, activatedAt)
    ]);
    if (!results[0]?.meta || results[0].meta.changes !== 1) {
      return json({ ok: false, error: "CODIGO_YA_UTILIZADO" }, 409);
    }
  } catch (_) {
    return json({ ok: false, error: "NO_SE_PUDO_ACTIVAR" }, 500);
  }
  return json({ ok: true, status: "active", activated_at: activatedAt, expires_at: expiresAt });
}


async function requestAccountRecovery(request, env) {
  const body = await request.json();
  const businessName = typeof body.business_name === "string" ? body.business_name.trim() : "";
  const phone = typeof body.whatsapp === "string" ? body.whatsapp.replace(/\D/g, "") : "";
  const installationId = typeof body.installation_id === "string" ? body.installation_id.trim() : "";
  if (!validText(businessName, 2, 80) || phone.length < 10 || phone.length > 15 ||
      !validText(installationId, 20, 100)) {
    return json({ ok: false, error: "DATOS_INVALIDOS" }, 400);
  }

  const db = env.DB;
  const ddl = [
    "CREATE TABLE IF NOT EXISTS account_recovery_requests (",
    "id TEXT PRIMARY KEY, folio TEXT NOT NULL UNIQUE, business_id TEXT NOT NULL,",
    "old_installation_id TEXT NOT NULL, new_installation_id TEXT NOT NULL, code_hash TEXT NOT NULL,",
    "attempt_count INTEGER NOT NULL DEFAULT 0, locked_at TEXT, consumed_at TEXT, invalidated_at TEXT,",
    "status TEXT NOT NULL DEFAULT 'pending', created_at TEXT NOT NULL, expires_at TEXT NOT NULL)"
  ].join(" ");
  await db.prepare(ddl).run();

  const business = await db.prepare(
    "SELECT id,business_name,whatsapp,installation_id,status FROM businesses WHERE business_name_normalized=? LIMIT 1"
  ).bind(normalizeName(businessName)).first();

  // Respuesta genérica para no revelar si existe una cuenta o si el teléfono coincide.
  if (!business || (business.whatsapp || "").replace(/\D/g, "") !== phone ||
      business.status === "blocked") {
    return json({ ok: false, error: "DATOS_NO_VERIFICADOS", message: "No pudimos validar los datos. Revisa el nombre del negocio y el WhatsApp registrado." }, 404);
  }
  if (business.installation_id === installationId) {
    return json({ ok: false, error: "DISPOSITIVO_YA_VINCULADO", message: "Este dispositivo ya está vinculado a este negocio." }, 409);
  }

  const deviceOwner = await db.prepare(
    "SELECT business_name FROM businesses WHERE installation_id=? AND id<>? LIMIT 1"
  ).bind(installationId, business.id).first();
  if (deviceOwner) {
    return json({ ok: false, error: "DISPOSITIVO_OCUPADO", message: "Este dispositivo ya está vinculado a otro negocio." }, 409);
  }

  const currentTime = nowIso();
  const pendingBusinessRequest = await db.prepare(
    "SELECT folio,expires_at FROM activation_requests WHERE business_id=? AND status='pending' AND expires_at>? ORDER BY created_at DESC LIMIT 1"
  ).bind(business.id, currentTime).first();
  if (pendingBusinessRequest && business.status !== "pending") {
    return json({ ok: false, error: "SOLICITUD_ACTIVA", folio: pendingBusinessRequest.folio,
      expires_at: pendingBusinessRequest.expires_at,
      message: "El negocio tiene una solicitud de pago pendiente. Espera a que termine antes de cambiar de dispositivo." }, 409);
  }

  const pendingRecovery = await db.prepare(
    "SELECT folio,expires_at FROM account_recovery_requests WHERE business_id=? AND status='pending' AND expires_at>? AND consumed_at IS NULL AND invalidated_at IS NULL ORDER BY created_at DESC LIMIT 1"
  ).bind(business.id, currentTime).first();
  if (pendingRecovery) {
    return json({ ok: false, error: "RECUPERACION_EN_CURSO", folio: pendingRecovery.folio,
      expires_at: pendingRecovery.expires_at,
      message: "Ya existe una recuperación pendiente para este negocio. Contacta al responsable de SENE." }, 409);
  }

  await db.prepare(
    "UPDATE account_recovery_requests SET status='expired',invalidated_at=? WHERE business_id=? AND status='pending' AND expires_at<=? AND consumed_at IS NULL"
  ).bind(currentTime, business.id, currentTime).run();

  const id = crypto.randomUUID();
  const folio = "TRAS-" + new Date().toISOString().slice(0, 10).replace(/-/g, "") + "-" + crypto.randomUUID().slice(0, 6).toUpperCase();
  const code = randomCode();
  const codeHash = await sha256(code);
  const expiresAt = plusHours(new Date(), 48);
  await db.prepare(
    "INSERT INTO account_recovery_requests (id,folio,business_id,old_installation_id,new_installation_id,code_hash,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?)"
  ).bind(id, folio, business.id, business.installation_id, installationId, codeHash, currentTime, expiresAt).run();

  const message = [
    "🔐 SOLICITUD DE CAMBIO DE DISPOSITIVO SENE",
    "Folio: " + folio,
    "Negocio: " + business.business_name,
    "WhatsApp registrado: " + phone,
    ...(pendingBusinessRequest ? ["Solicitud de activación pendiente: " + pendingBusinessRequest.folio] : []),
    "Código de autorización (ENTREGAR SOLO DESPUÉS DE VERIFICAR LA IDENTIDAD): " + code,
    "Vigencia: 48 horas",
    "El dispositivo anterior seguirá vinculado hasta que el cliente introduzca el código."
  ].join("\n");

  try {
    await telegram(env, message);
  } catch (error) {
    await db.prepare(
      "UPDATE account_recovery_requests SET status='cancelled',invalidated_at=? WHERE id=? AND status='pending'"
    ).bind(nowIso(), id).run();
    return json({ ok: false, error: "NOTIFICACION_TELEGRAM_FALLO", message: "No se pudo avisar al responsable por Telegram. Intenta más tarde." }, 503);
  }

  return json({ ok: true, folio, expires_at: expiresAt,
    activation_folio: pendingBusinessRequest?.folio || null,
    message: "Solicitud recibida. El responsable verificará tu identidad antes de entregarte el código." }, 201);
}

async function completeAccountRecovery(request, env) {
  const body = await request.json();
  const folio = typeof body.folio === "string" ? body.folio.trim().toUpperCase() : "";
  const code = typeof body.code === "string" ? body.code.trim().toUpperCase() : "";
  const installationId = typeof body.installation_id === "string" ? body.installation_id.trim() : "";
  if (!validText(folio, 8, 50) || !/^[A-HJ-NP-Z2-9]{10}$/.test(code) ||
      !validText(installationId, 20, 100)) {
    return json({ ok: false, error: "DATOS_INVALIDOS" }, 400);
  }

  const db = env.DB;
  const row = await db.prepare(
    "SELECT r.*, b.status AS business_status, b.installation_id AS current_installation_id FROM account_recovery_requests r JOIN businesses b ON b.id=r.business_id WHERE r.folio=?"
  ).bind(folio).first();

  if (!row) return json({ ok: false, error: "FOLIO_NO_ENCONTRADO" }, 404);
  if (row.status !== "pending" || row.consumed_at || row.invalidated_at) {
    return json({ ok: false, error: "SOLICITUD_NO_DISPONIBLE" }, 409);
  }
  if (new Date(row.expires_at).getTime() <= Date.now()) {
    await db.prepare("UPDATE account_recovery_requests SET status='expired',invalidated_at=? WHERE id=?")
      .bind(nowIso(), row.id).run();
    return json({ ok: false, error: "SOLICITUD_EXPIRADA" }, 410);
  }
  if (row.locked_at || row.attempt_count >= 5) {
    return json({ ok: false, error: "CODIGO_BLOQUEADO" }, 429);
  }
  if (row.new_installation_id !== installationId ||
      row.current_installation_id !== row.old_installation_id ||
      row.business_status === "blocked") {
    return json({ ok: false, error: "DISPOSITIVO_NO_AUTORIZADO" }, 403);
  }

  const suppliedHash = await sha256(code);
  if (suppliedHash !== row.code_hash) {
    await db.prepare("UPDATE account_recovery_requests SET attempt_count=attempt_count+1,locked_at=CASE WHEN attempt_count+1>=5 THEN ? ELSE locked_at END WHERE id=?")
      .bind(nowIso(), row.id).run();
    return json({ ok: false, error: "CODIGO_INCORRECTO" }, 401);
  }

  const completedAt = nowIso();
  const results = await db.batch([
    db.prepare("UPDATE account_recovery_requests SET consumed_at=?,status='completed' WHERE id=? AND status='pending' AND consumed_at IS NULL AND invalidated_at IS NULL AND EXISTS (SELECT 1 FROM businesses WHERE id=? AND installation_id=?)")
      .bind(completedAt, row.id, row.business_id, row.old_installation_id),
    db.prepare("UPDATE businesses SET installation_id=?,updated_at=? WHERE id=? AND installation_id=? AND EXISTS (SELECT 1 FROM account_recovery_requests WHERE id=? AND consumed_at=?)")
      .bind(installationId, completedAt, row.business_id, row.old_installation_id, row.id, completedAt)
  ]);
  if (!results[0]?.meta || results[0].meta.changes !== 1 ||
      !results[1]?.meta || results[1].meta.changes !== 1) {
    // Compensa el primer UPDATE si el segundo no pudo cambiar el dispositivo.
    // Solo revierte esta operación concreta; no pisa una transferencia posterior.
    await db.batch([
      db.prepare("UPDATE businesses SET installation_id=?,updated_at=? WHERE id=? AND installation_id=? AND updated_at=?")
        .bind(row.old_installation_id, nowIso(), row.business_id, installationId, completedAt),
      db.prepare("UPDATE account_recovery_requests SET consumed_at=NULL,status='pending' WHERE id=? AND consumed_at=? AND EXISTS (SELECT 1 FROM businesses WHERE id=? AND installation_id=?)")
        .bind(row.id, completedAt, row.business_id, row.old_installation_id)
    ]);
    return json({ ok: false, error: "NO_SE_PUDO_TRANSFERIR", message: "La cuenta cambió mientras se procesaba la solicitud. Contacta al responsable de SENE." }, 409);
  }

  const pendingActivation = await db.prepare(
    "SELECT folio FROM activation_requests WHERE business_id=? AND status='pending' AND expires_at>? ORDER BY created_at DESC LIMIT 1"
  ).bind(row.business_id, nowIso()).first();
  return json({ ok: true, status: row.business_status === "pending" ? "activation_device_rebound" : "transferred",
    activation_folio: pendingActivation?.folio || null,
    message: row.business_status === "pending"
      ? "Dispositivo autorizado para continuar la activación pendiente."
      : "Dispositivo autorizado. La suscripción y su fecha de vencimiento se conservaron." });
}


async function transferAccountDevice(request, env) {
  const body = await request.json();
  const folio = typeof body.folio === "string" ? body.folio.trim().toUpperCase() : "";
  const code = typeof body.code === "string" ? body.code.trim().toUpperCase() : "";
  const installationId = typeof body.installation_id === "string" ? body.installation_id.trim() : "";
  if (!validText(folio, 8, 50) || !/^[A-HJ-NP-Z2-9]{10}$/.test(code) ||
      !validText(installationId, 20, 100)) {
    return json({ ok: false, error: "DATOS_INVALIDOS" }, 400);
  }

  const db = env.DB;
  const row = await db.prepare(`
    SELECT ar.id AS request_id, ar.business_id, ar.status AS request_status,
           ar.request_type, ar.expires_at AS request_expires,
           ac.id AS code_id, ac.code_hash, ac.consumed_at, ac.invalidated_at,
           ac.attempt_count, ac.locked_at,
           b.installation_id, b.status AS business_status, b.expires_at AS business_expires
    FROM activation_requests ar
    JOIN activation_codes ac ON ac.request_id=ar.id
    JOIN businesses b ON b.id=ar.business_id
    WHERE ar.folio=?
    ORDER BY ac.created_at DESC LIMIT 1
  `).bind(folio).first();

  if (!row) return json({ ok: false, error: "FOLIO_NO_ENCONTRADO" }, 404);
  if (row.request_status !== "completed" || !row.consumed_at || row.invalidated_at) {
    return json({ ok: false, error: "LICENCIA_NO_TRANSFERIBLE", message: "La transferencia requiere el folio y código de una licencia ya activada." }, 409);
  }
  if (row.locked_at || Number(row.attempt_count) >= 5) {
    return json({ ok: false, error: "CODIGO_BLOQUEADO" }, 429);
  }
  if (row.business_status !== "active" || !row.business_expires ||
      new Date(row.business_expires).getTime() <= Date.now()) {
    return json({ ok: false, error: "LICENCIA_NO_ACTIVA" }, 403);
  }
  if (row.installation_id === installationId) {
    return json({ ok: true, status: "already_linked", message: "Este dispositivo ya está autorizado." });
  }

  const owner = await db.prepare(
    "SELECT id FROM businesses WHERE installation_id=? AND id<>? LIMIT 1"
  ).bind(installationId, row.business_id).first();
  if (owner) return json({ ok: false, error: "DISPOSITIVO_OCUPADO" }, 409);

  const suppliedHash = await sha256(code);
  if (suppliedHash !== row.code_hash) {
    await db.prepare("UPDATE activation_codes SET attempt_count=attempt_count+1, locked_at=CASE WHEN attempt_count+1>=5 THEN ? ELSE locked_at END WHERE id=? AND consumed_at IS NOT NULL AND invalidated_at IS NULL")
      .bind(nowIso(), row.code_id).run();
    return json({ ok: false, error: "CODIGO_INCORRECTO" }, 401);
  }

  const transferredAt = nowIso();
  const results = await db.batch([
    db.prepare("UPDATE businesses SET installation_id=?,updated_at=? WHERE id=? AND installation_id=? AND status='active' AND expires_at>? AND EXISTS (SELECT 1 FROM activation_codes WHERE id=? AND consumed_at IS NOT NULL AND invalidated_at IS NULL)")
      .bind(installationId, transferredAt, row.business_id, row.installation_id, transferredAt, row.code_id),
    db.prepare("UPDATE admin_sessions SET revoked_at=? WHERE business_id=? AND revoked_at IS NULL")
      .bind(transferredAt, row.business_id)
  ]);
  if (!results[0]?.meta || results[0].meta.changes !== 1) {
    return json({ ok: false, error: "NO_SE_PUDO_TRANSFERIR", message: "La cuenta cambió mientras se procesaba la transferencia. Vuelve a intentarlo." }, 409);
  }

  return json({
    ok: true,
    status: "transferred",
    message: "Dispositivo autorizado. Se conservó el mismo folio, la licencia y su fecha de vencimiento."
  });
}

async function subscriptionStatus(request, env) {
  const body = await request.json();
  const installationId = typeof body.installation_id === "string" ? body.installation_id.trim() : "";
  if (!validText(installationId, 20, 100)) return json({ ok: false, error: "INSTALACION_INVALIDA" }, 400);

  const db = env.DB;
  let row = await db.prepare(
    "SELECT id,business_name,status,expires_at FROM businesses WHERE installation_id=? LIMIT 1"
  ).bind(installationId).first();

  const expiryTime = row?.expires_at ? new Date(row.expires_at).getTime() : 0;
  const hasExpired = !!row && expiryTime > 0 && expiryTime <= Date.now();

  // El servidor cambia el estado de la licencia al vencer los 30 días.
  // No elimina el negocio ni su historial: queda disponible para renovación.
  if (hasExpired && row.status === "active") {
    await db.prepare(
      "UPDATE businesses SET status='expired',updated_at=? WHERE id=? AND status='active' AND expires_at<=?"
    ).bind(nowIso(), row.id, nowIso()).run();
    row.status = "expired";
  }

  const active = !!row && row.status === "active" && expiryTime > Date.now();
  const expired = !!row && row.status === "expired" && hasExpired;
  const reportKey = row ? await db.prepare("SELECT public_key FROM admin_public_keys WHERE business_id=?")
    .bind(row.id).first() : null;
  return json({ ok: true, linked: !!row, active, expired,
    business_name: row?.business_name || null,
    expires_at: row?.expires_at || null,
    rp: reportKey?.public_key || "",
    message: active ? "Suscripción vigente." : expired ? "LICENCIA_VENCIDA" : "Se requiere vincular o renovar SENE VENTAS." });
}



// ---- SENE VENTAS / SeneAdmin durable synchronization API ----
const readJson = async request => {
  try { return await request.json(); } catch (_) { return null; }
};
const asArray = value => Array.isArray(value) ? value : [];
const eventId = value => String(value?.e || value?.event_id || value?.id || "").trim();
const eventWorker = value => String(value?.w || value?.worker_id || value?.i_worker || "").trim();
const parseCursor = value => {
  const n = Number.parseInt(String(value || "0"), 10);
  return Number.isSafeInteger(n) && n >= 0 ? n : 0;
};
const encodeRow = row => {
  try { return JSON.parse(row.payload); } catch (_) { return null; }
};
async function activeBusinessByInstallation(db, installationId) {
  if (!validText(installationId, 20, 100)) return null;
  const row = await db.prepare(
    "SELECT id,business_name,status,expires_at,installation_id FROM businesses WHERE installation_id=? LIMIT 1"
  ).bind(installationId).first();
  if (!row || row.status !== "active" || !row.expires_at || new Date(row.expires_at).getTime() <= Date.now()) return null;
  return row;
}
async function adminSession(db, token) {
  if (!validText(token, 24, 256)) return null;
  const tokenHash = await sha256(token);
  const row = await db.prepare(
    "SELECT s.id,s.business_id,s.admin_installation_id,b.business_name,b.status,b.expires_at FROM admin_sessions s JOIN businesses b ON b.id=s.business_id WHERE s.token_hash=? AND s.revoked_at IS NULL LIMIT 1"
  ).bind(tokenHash).first();
  if (!row || row.status !== "active" || !row.expires_at || new Date(row.expires_at).getTime() <= Date.now()) return null;
  await db.prepare("UPDATE admin_sessions SET last_seen_at=? WHERE id=? AND revoked_at IS NULL")
    .bind(nowIso(), row.id).run();
  return row;
}
async function createAdminPairing(request, env) {
  const body = await readJson(request);
  const installationId = String(body?.installation_id || "").trim();
  if (!validText(installationId, 20, 100)) return json({ok:false,error:"INSTALACION_INVALIDA"},400);
  const business = await activeBusinessByInstallation(env.DB, installationId);
  if (!business) return json({ok:false,error:"LICENCIA_NO_ACTIVA",message:"Se requiere una licencia SENE VENTAS activa para vincular SeneAdmin."},403);
  const code = randomCode();
  const created = nowIso();
  const expires = new Date(Date.now() + 10 * 60 * 1000).toISOString();
  const id = crypto.randomUUID();
  await env.DB.batch([
    env.DB.prepare("UPDATE admin_pairing_codes SET invalidated_at=? WHERE business_id=? AND consumed_at IS NULL AND invalidated_at IS NULL")
      .bind(created,business.id),
    env.DB.prepare("INSERT INTO admin_pairing_codes (id,business_id,installation_id,code_hash,created_at,expires_at) VALUES (?,?,?,?,?,?)")
      .bind(id,business.id,installationId,await sha256(code),created,expires)
  ]);
  return json({ok:true,pairing_code:code,expires_at:expires},201);
}
async function validReportPublicKey(encoded) {
  try {
    const normalized=encoded.replace(/-/g,"+").replace(/_/g,"/").replace(/=+$/,"");
    const padded=normalized+"=".repeat((4-normalized.length%4)%4);
    const raw=Uint8Array.from(atob(padded),c=>c.charCodeAt(0));
    const key=await crypto.subtle.importKey("spki",raw,{name:"RSA-OAEP",hash:"SHA-256"},false,["encrypt"]);
    return key.algorithm?.name==="RSA-OAEP" && key.algorithm.modulusLength>=2048;
  } catch (_) { return false; }
}
async function claimAdminPairing(request, env) {
  const body = await readJson(request);
  const code = String(body?.pairing_code || "").trim().toUpperCase();
  const adminInstallationId = String(body?.admin_installation_id || "").trim();
  const publicKey = String(body?.rp || "").trim();
  if (!/^[A-HJ-NP-Z2-9]{10}$/.test(code) || !validText(adminInstallationId,20,100) ||
      publicKey.length < 200 || publicKey.length > 12000 || !/^[A-Za-z0-9_+/-]+={0,2}$/.test(publicKey) ||
      !(await validReportPublicKey(publicKey))) {
    return json({ok:false,error:"DATOS_INVALIDOS"},400);
  }
  const row = await env.DB.prepare(
    "SELECT p.*,b.business_name,b.status AS business_status,b.expires_at AS business_expires FROM admin_pairing_codes p JOIN businesses b ON b.id=p.business_id WHERE p.code_hash=? LIMIT 1"
  ).bind(await sha256(code)).first();
  if (!row) return json({ok:false,error:"CODIGO_NO_ENCONTRADO"},404);
  if (row.consumed_at || row.invalidated_at) return json({ok:false,error:"CODIGO_NO_DISPONIBLE"},409);
  if (new Date(row.expires_at).getTime() <= Date.now()) return json({ok:false,error:"CODIGO_EXPIRADO"},410);
  if (row.business_status !== "active" || !row.business_expires || new Date(row.business_expires).getTime() <= Date.now()) {
    return json({ok:false,error:"LICENCIA_NO_ACTIVA"},403);
  }
  const tokenBytes = new Uint8Array(32); crypto.getRandomValues(tokenBytes);
  const token = Array.from(tokenBytes,b=>b.toString(16).padStart(2,"0")).join("");
  const tokenHash = await sha256(token);
  const now = nowIso();
  const sessionId = crypto.randomUUID();
  try {
    const results = await env.DB.batch([
      env.DB.prepare("UPDATE admin_pairing_codes SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at>?")
        .bind(now,row.id,now),
      env.DB.prepare("INSERT INTO admin_sessions (id,business_id,admin_installation_id,token_hash,created_at,last_seen_at,revoked_at) SELECT ?,?,?,?,?,?,NULL WHERE EXISTS (SELECT 1 FROM admin_pairing_codes WHERE id=? AND consumed_at=?) ON CONFLICT(business_id) DO UPDATE SET id=excluded.id,admin_installation_id=excluded.admin_installation_id,token_hash=excluded.token_hash,created_at=excluded.created_at,last_seen_at=excluded.last_seen_at,revoked_at=NULL")
        .bind(sessionId,row.business_id,adminInstallationId,tokenHash,now,now,row.id,now),
      env.DB.prepare("INSERT INTO admin_public_keys (business_id,admin_installation_id,public_key,updated_at) SELECT ?,?,?,? WHERE EXISTS (SELECT 1 FROM admin_pairing_codes WHERE id=? AND consumed_at=?) ON CONFLICT(business_id) DO UPDATE SET admin_installation_id=excluded.admin_installation_id,public_key=excluded.public_key,updated_at=excluded.updated_at")
        .bind(row.business_id,adminInstallationId,publicKey,now,row.id,now)
    ]);
    if (!results[0]?.meta || results[0].meta.changes !== 1) return json({ok:false,error:"CODIGO_YA_UTILIZADO"},409);
  } catch (_) { return json({ok:false,error:"NO_SE_PUDO_VINCULAR"},500); }
  return json({ok:true,admin_token:token,business_id:row.business_id,business_name:row.business_name},200);
}
async function payrollSync(request, env) {
  const body = await readJson(request);
  const installationId = String(body?.i || "").trim();
  if (Number(body?.v) !== 1 || body?.t !== "[SYNC]" || !validText(installationId,20,100))
    return json({ok:false,error:"PROTOCOLO_INVALIDO"},400);
  const business = await activeBusinessByInstallation(env.DB,installationId);
  if (!business) return json({ok:false,error:"LICENCIA_NO_ACTIVA"},403);
  const db=env.DB, now=nowIso();
  const requests=asArray(body.q ?? body.r).slice(0,100), attendance=asArray(body.h).slice(0,100), inventory=asArray(body.g).slice(0,100), reports=asArray(body.b).slice(0,100);
  const statements=[];
  for (const item of requests) {
    const id=eventId(item), worker=eventWorker(item) || String(body.w || "").trim();
    if (!id || !worker || !["[P$]","[C$]"].includes(String(item.t||""))) continue;
    statements.push(db.prepare("INSERT INTO payroll_requests (event_id,business_id,worker_id,payload,status,created_at,updated_at) VALUES (?,?,?,?, 'PENDING',?,?) ON CONFLICT(business_id,event_id) DO NOTHING")
      .bind(id,business.id,worker,JSON.stringify(item),now,now));
  }
  const acceptedH=[],acceptedG=[],acceptedB=[];
  for (const item of attendance) {
    const id=eventId(item); if(!id) continue;
    statements.push(db.prepare("INSERT INTO attendance_events (event_id,business_id,worker_id,payload,created_at) VALUES (?,?,?,?,?) ON CONFLICT(business_id,event_id) DO NOTHING")
      .bind(id,business.id,eventWorker(item)||null,JSON.stringify(item),now));
    acceptedH.push(id);
  }
  for (const item of inventory) {
    const id=eventId(item); if(!id) continue;
    statements.push(db.prepare("INSERT INTO inventory_events (event_id,business_id,worker_id,payload,created_at) VALUES (?,?,?,?,?) ON CONFLICT(business_id,event_id) DO NOTHING")
      .bind(id,business.id,eventWorker(item)||null,JSON.stringify(item),now));
    acceptedG.push(id);
  }
  for (const item of reports) {
    const id=eventId(item); if(!id) continue;
    statements.push(db.prepare("INSERT INTO report_events (event_id,business_id,worker_id,envelope,created_at) VALUES (?,?,?,?,?) ON CONFLICT(business_id,event_id) DO NOTHING")
      .bind(id,business.id,eventWorker(item)||null,JSON.stringify(item),now));
    acceptedB.push(id);
  }
  if (statements.length) await db.batch(statements);
  await maybeCleanupRetainedHistory(db);
  const decisions=await db.prepare("SELECT event_id,status,decision_by,decision_reason,decided_at FROM payroll_requests WHERE business_id=? AND status IN ('APPROVED','REJECTED') ORDER BY seq LIMIT 300")
    .bind(business.id).all();
  const key=await db.prepare("SELECT public_key FROM admin_public_keys WHERE business_id=?").bind(business.id).first();
  // Las decisiones se reenvían de forma idempotente en cada sincronización.
  // No se devuelve un cursor aquí: esta ruta no pagina decisiones y no debe
  // depender de una variable inexistente que interrumpa la sincronización.
  const responseDecisions=(decisions.results||[]).map(x=>({
    e:x.event_id,x:x.status,by:x.decision_by||"",rr:x.decision_reason||"",r:x.decision_reason||"",at:x.decided_at||""
  }));
  return json({
    v:1,d:responseDecisions,
    ha:acceptedH,ga:acceptedG,ba:acceptedB,
    rp:key?.public_key||""
  });
}
async function adminPayrollSync(request, env) {
  const body=await readJson(request);
  if(Number(body?.v)!==1 || body?.t!=="[SYNC]") return json({ok:false,error:"PROTOCOLO_INVALIDO"},400);
  const session=await adminSession(env.DB,String(body?.a||""));
  if(!session) return json({ok:false,error:"ADMIN_NO_AUTORIZADO"},401);
  const db=env.DB,businessId=session.business_id,now=nowIso();
  const publicKey=String(body?.rp||"").trim();
  if(publicKey.length>=200 && publicKey.length<=12000 && /^[A-Za-z0-9_+/-]+={0,2}$/.test(publicKey) && await validReportPublicKey(publicKey)) {
    await db.prepare("INSERT INTO admin_public_keys (business_id,admin_installation_id,public_key,updated_at) VALUES (?,?,?,?) ON CONFLICT(business_id) DO UPDATE SET admin_installation_id=excluded.admin_installation_id,public_key=excluded.public_key,updated_at=excluded.updated_at")
      .bind(businessId,session.admin_installation_id,publicKey,now).run();
  }
  const decisions=asArray(body.d), statements=[];
  for(const d of decisions) {
    const id=eventId(d), state=String(d.x||"").toUpperCase();
    if(!id || !["[OK]","[NO]","APPROVED","REJECTED"].includes(state)) continue;
    const normalized=state==="[OK]"||state==="APPROVED"?"APPROVED":"REJECTED";
    statements.push(db.prepare("UPDATE payroll_requests SET status=?,decision_by='PATRON',decision_reason=?,decided_at=?,updated_at=? WHERE business_id=? AND event_id=? AND status='PENDING'")
      .bind(normalized,String(d.r||""),now,now,businessId,id));
  }
  const attendanceAcks=asArray(body.ha).map(x=>typeof x==="string"?x:eventId(x)).filter(Boolean);
  const inventoryAcks=asArray(body.gi).map(x=>typeof x==="string"?x:eventId(x)).filter(Boolean);
  const reportAcks=asArray(body.ba).map(x=>typeof x==="string"?x:eventId(x)).filter(Boolean);
  for(const id of attendanceAcks) statements.push(db.prepare("UPDATE attendance_events SET delivered_at=COALESCE(delivered_at,?) WHERE business_id=? AND event_id=?").bind(now,businessId,id));
  for(const id of inventoryAcks) statements.push(db.prepare("UPDATE inventory_events SET delivered_at=COALESCE(delivered_at,?) WHERE business_id=? AND event_id=?").bind(now,businessId,id));
  for(const id of reportAcks) statements.push(db.prepare("UPDATE report_events SET delivered_at=COALESCE(delivered_at,?) WHERE business_id=? AND event_id=?").bind(now,businessId,id));
  if(statements.length) await db.batch(statements);
  await maybeCleanupRetainedHistory(db);

  const requestCursor=parseCursor(body.c);
  const reqRows=await db.prepare("SELECT seq,event_id,payload,status,decision_by,decision_reason,decided_at FROM payroll_requests WHERE business_id=? AND seq>? ORDER BY seq LIMIT 300")
    .bind(businessId,requestCursor).all();
  // Las decisiones resueltas se vuelven a entregar aunque su seq sea anterior al cursor.
  // El cliente las aplica idempotentemente y deja de reenviar su decisión local al recibirlas.
  const decisionRows=await db.prepare("SELECT seq,event_id,payload,status,decision_by,decision_reason,decided_at FROM payroll_requests WHERE business_id=? AND status IN ('APPROVED','REJECTED') ORDER BY updated_at DESC,seq DESC LIMIT 300")
    .bind(businessId).all();
  const mergedRows=new Map();
  for(const row of [...(reqRows.results||[]),...(decisionRows.results||[])]) mergedRows.set(row.event_id,row);
  const reqs=[...mergedRows.values()].sort((a,b)=>a.seq-b.seq).map(row=>{
    const p=(()=>{try{return JSON.parse(row.payload)}catch(_){return {}}})();
    return {...p,e:row.event_id,x:row.status,by:row.decision_by||"",rr:row.decision_reason||"",at:row.decided_at||""};
  });
  const hRows=await db.prepare("SELECT seq,payload FROM attendance_events WHERE business_id=? AND delivered_at IS NULL ORDER BY seq LIMIT 300").bind(businessId).all();
  const gRows=await db.prepare("SELECT seq,payload FROM inventory_events WHERE business_id=? AND delivered_at IS NULL ORDER BY seq LIMIT 300").bind(businessId).all();
  const bRows=await db.prepare("SELECT seq,event_id,envelope FROM report_events WHERE business_id=? AND delivered_at IS NULL ORDER BY seq LIMIT 100").bind(businessId).all();
  const maxSeq=reqRows.results?.length?reqRows.results[reqRows.results.length-1].seq:requestCursor;
  const parsePayload=rows=>(rows||[]).map(r=>{try{return JSON.parse(r.payload)}catch(_){return null}}).filter(Boolean);
  return json({
    v:1,items:reqs,
    h:parsePayload(hRows.results),g:parsePayload(gRows.results),
    b:(bRows.results||[]).map(r=>{try{return JSON.parse(r.envelope)}catch(_){return {e:r.event_id,p:r.envelope}}}),
    haa:attendanceAcks,gaa:inventoryAcks,baa:reportAcks,
    n:String(maxSeq)
  });
}

async function cleanupRetainedHistory(db) {
  // Solo elimina historial ya entregado/resuelto, nunca colas pendientes de ACK.
  // En cada limpieza conserva exactamente los 10 más recientes por negocio y categoría.
  // Los registros pendientes nunca entran en estas consultas.
  await db.batch([
    db.prepare(`
      DELETE FROM payroll_requests
      WHERE seq IN (
        SELECT seq FROM (
          SELECT seq, created_at,
            ROW_NUMBER() OVER (
              PARTITION BY business_id, COALESCE(json_extract(payload, '$.t'), 'OTROS')
              ORDER BY created_at DESC, seq DESC
            ) AS rn
          FROM payroll_requests
          WHERE status IN ('APPROVED','REJECTED')
        )
        WHERE rn > 10
      )
    `),
    db.prepare(`
      DELETE FROM report_events
      WHERE seq IN (
        SELECT seq FROM (
          SELECT seq, created_at,
            ROW_NUMBER() OVER (
              PARTITION BY business_id, COALESCE(json_extract(envelope, '$.k'), 'OTROS')
              ORDER BY created_at DESC, seq DESC
            ) AS rn
          FROM report_events
          WHERE delivered_at IS NOT NULL
        )
        WHERE rn > 10
      )
    `),
    db.prepare(`
      DELETE FROM inventory_events
      WHERE seq IN (
        SELECT seq FROM (
          SELECT seq, created_at,
            ROW_NUMBER() OVER (
              PARTITION BY business_id, COALESCE(json_extract(payload, '$.g'), 'OTROS')
              ORDER BY created_at DESC, seq DESC
            ) AS rn
          FROM inventory_events
          WHERE delivered_at IS NOT NULL
        )
        WHERE rn > 10
      )
    `),
    db.prepare(`
      DELETE FROM attendance_events
      WHERE seq IN (
        SELECT seq FROM (
          SELECT seq, created_at,
            ROW_NUMBER() OVER (
              PARTITION BY business_id, COALESCE(json_extract(payload, '$.t'), 'OTROS')
              ORDER BY created_at DESC, seq DESC
            ) AS rn
          FROM attendance_events
          WHERE delivered_at IS NOT NULL
        )
        WHERE rn > 10
      )
    `)
  ]);
}

async function maybeCleanupRetainedHistory(db) {
  // Respaldo automático incluso si el Worker aún no tiene Cron Trigger configurado.
  // La limpieza se evalúa en la actividad de sincronización y se ejecuta como máximo cada 30 días.
  try {
    const last = await db.prepare(
      "SELECT updated_at FROM server_sync_state WHERE business_id='__SYSTEM__' AND channel='retention' LIMIT 1"
    ).first();
    const previous = last?.updated_at ? new Date(last.updated_at).getTime() : 0;
    if (previous > 0 && Date.now() - previous < 30 * 24 * 60 * 60 * 1000) return;
    await cleanupRetainedHistory(db);
    const now = nowIso();
    await db.prepare(
      "INSERT INTO server_sync_state (business_id,channel,last_seq,updated_at) VALUES ('__SYSTEM__','retention',0,?) ON CONFLICT(business_id,channel) DO UPDATE SET updated_at=excluded.updated_at"
    ).bind(now).run();
  } catch (_) {
    // La limpieza nunca debe interrumpir la sincronización normal.
  }
}

export default {
  // Ejecución automática programada: vence licencias y limpia solo historial ya confirmado.
  async scheduled(controller, env, ctx) {
    const now = nowIso();
    ctx.waitUntil((async () => {
      await env.DB.prepare(
        "UPDATE businesses SET status='expired',updated_at=? WHERE status='active' AND expires_at IS NOT NULL AND expires_at<=?"
      ).bind(now, now).run();
      // El Cron puede ejecutarse diariamente; el marcador D1 limita la limpieza real a cada 30 días.
      await maybeCleanupRetainedHistory(env.DB);
    })());
  },

  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });
    const url = new URL(request.url);
    try {
      if (request.method === "GET" && url.pathname === "/health") {
        const check = await env.DB.prepare("SELECT 1 AS ok").first();
        return json({ servidor: "SERVER-SENE", estado: "funcionando", base_de_datos: check?.ok === 1 ? "conectada" : "sin respuesta" });
      }
      if (request.method === "GET" && url.pathname === "/config") {
        const activationAmount = await getAmount("activation");
        const renewalAmount = await getAmount("renewal");
        return json({ ok: true, amount_cents: activationAmount, activation_amount_cents: activationAmount, renewal_amount_cents: renewalAmount, currency: "MXN" });
      }
      if (request.method === "POST" && url.pathname === "/requests") return await createRequest(request, env);
      if (request.method === "POST" && url.pathname === "/redeem") return await redeemCode(request, env);
      if (request.method === "POST" && url.pathname === "/account/recovery/request") return await requestAccountRecovery(request, env);
      if (request.method === "POST" && url.pathname === "/account/recovery/complete") return await completeAccountRecovery(request, env);
      if (request.method === "POST" && url.pathname === "/account/device/transfer") return await transferAccountDevice(request, env);
      if (request.method === "POST" && url.pathname === "/subscription/status") return await subscriptionStatus(request, env);
      if (request.method === "POST" && url.pathname === "/admin/pairing/create") return await createAdminPairing(request, env);
      if (request.method === "POST" && url.pathname === "/admin/pairing/claim") return await claimAdminPairing(request, env);
      if (request.method === "POST" && url.pathname === "/payroll/requests/sync") return await payrollSync(request, env);
      if (request.method === "POST" && url.pathname === "/admin/payroll/requests/sync") return await adminPayrollSync(request, env);
      return json({ ok: false, error: "RUTA_NO_ENCONTRADA" }, 404);
    } catch (error) {
      console.error("SERVER-SENE API error:", error?.message || error);
      return json({ ok: false, error: "ERROR_INTERNO" }, 500);
    }
  }
};
