/**
 * SERVER-SENE Worker API
 * Cloudflare Worker + D1 binding DB.
 * Secrets: GUARDIAN_OWNER_EVENTS_URL, GUARDIAN_DELIVERY_ENC_KEY, GUARDIAN_DELIVERY_HMAC_KEY.
 * Optional generic alert: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID.
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

async function telegram(env, message) {
  if (!env.TELEGRAM_BOT_TOKEN || !env.TELEGRAM_CHAT_ID) throw new Error("Telegram secrets missing");
  const response = await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chat_id: env.TELEGRAM_CHAT_ID, text: message })
  });
  if (!response.ok) throw new Error("Telegram notification failed");
  const result = await response.json();
  if (!result.ok) throw new Error("Telegram notification rejected");
}

function decodeBase64Url(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid_key");
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(normalized + "=".repeat((4 - normalized.length % 4) % 4));
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
function encodeBase64Url(value) {
  return btoa(String.fromCharCode(...new Uint8Array(value)))
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
async function sendOwnerMuseLicenseEvent(env, event) {
  const endpoint = typeof env.GUARDIAN_OWNER_EVENTS_URL === "string" ? env.GUARDIAN_OWNER_EVENTS_URL.trim() : "";
  if (!endpoint || !env.GUARDIAN_DELIVERY_ENC_KEY || !env.GUARDIAN_DELIVERY_HMAC_KEY)
    throw new Error("owner_muse_delivery_not_configured");
  const target = new URL(endpoint);
  if (target.protocol !== "https:" || target.pathname !== "/v1/guardian/owner-events" || target.search || target.hash)
    throw new Error("owner_muse_endpoint_invalid");
  const aesRaw = decodeBase64Url(env.GUARDIAN_DELIVERY_ENC_KEY);
  const hmacRaw = decodeBase64Url(env.GUARDIAN_DELIVERY_HMAC_KEY);
  if (aesRaw.byteLength !== 32 || hmacRaw.byteLength < 32) throw new Error("owner_muse_key_invalid");
  const aesKey = await crypto.subtle.importKey("raw", aesRaw, "AES-GCM", false, ["encrypt"]);
  const hmacKey = await crypto.subtle.importKey("raw", hmacRaw, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const eventId = typeof event.event_id === "string" ? event.event_id : crypto.randomUUID();
  event.event_id = eventId;
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plaintext = new TextEncoder().encode(JSON.stringify(event));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: new TextEncoder().encode(eventId), tagLength: 128 },
    aesKey, plaintext
  );
  const ivText = encodeBase64Url(iv);
  const ciphertextText = encodeBase64Url(ciphertext);
  const timestamp = String(Math.floor(Date.now() / 1000));
  const canonical = [timestamp, eventId, ivText, ciphertextText].join(".");
  const signature = encodeBase64Url(await crypto.subtle.sign("HMAC", hmacKey, new TextEncoder().encode(canonical)));
  const response = await fetch(target.toString(), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Sene-Timestamp": timestamp,
      "X-Sene-Event-Id": eventId,
      "X-Sene-Signature": signature
    },
    body: JSON.stringify({ event_id: eventId, iv: ivText, ciphertext: ciphertextText })
  });
  if (!response.ok) throw new Error("owner_muse_delivery_rejected");
  const result = await response.json();
  if (result?.accepted !== true) throw new Error("owner_muse_delivery_not_accepted");
}

async function getAmount(db) {
  const row = await db.prepare("SELECT setting_value FROM app_settings WHERE setting_key='amount_cents'").first();
  const amount = Number(row?.setting_value);
  if (!Number.isSafeInteger(amount) || amount < 1) throw new Error("amount_cents is not configured");
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

  const amount = await getAmount(db);
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

  const message = [
    "📩 NUEVA SOLICITUD SERVER-SENE",
    "Folio: " + folio,
    "Tipo: " + (type === "activation" ? "ACTIVACIÓN" : "RENOVACIÓN"),
    "Aviso: consulta los datos y el código exclusivamente en el MUSE del propietario."
  ].join("\n");

  try {
    // The encrypted owner MUSE delivery is mandatory. If it fails, the request
    // is cancelled and its code invalidated; no client MUSE receives the event.
    await sendOwnerMuseLicenseEvent(env, {
      event_id: requestId,
      event_type: type === "activation" ? "activation.requested" : "renewal.requested",
      installation_id: installationId,
      folio,
      request_type: type,
      created_at: createdAt,
      expires_at: expiresAt,
      payment_status: "pending",
      amount_cents: amount,
      currency: "MXN",
      business_name: businessName,
      holder_name: holder,
      whatsapp: phone,
      code
    });
  } catch (error) {
    await db.batch([
      db.prepare("UPDATE notifications SET status='failed' WHERE request_id=?").bind(requestId),
      db.prepare("UPDATE activation_requests SET status='cancelled' WHERE id=? AND status='pending'").bind(requestId),
      db.prepare("UPDATE activation_codes SET invalidated_at=? WHERE request_id=? AND consumed_at IS NULL").bind(nowIso(), requestId)
    ]);
    return json({ ok: false, error: "ENTREGA_MUSE_PROPIETARIO_FALLO", message: "No se pudo entregar la solicitud al MUSE del propietario. No se emitió un código utilizable; intenta nuevamente más tarde.", folio }, 503);
  }

  // Telegram is now an optional generic alert only; it never receives license
  // data or codes and cannot block a successfully delivered owner request.
  try {
    await telegram(env, message);
    await db.prepare("UPDATE notifications SET status='sent',sent_at=? WHERE request_id=?")
      .bind(nowIso(), requestId).run();
  } catch (error) {
    await db.prepare("UPDATE notifications SET status='failed' WHERE request_id=?")
      .bind(requestId).run();
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
  return json({ ok: true, linked: !!row, active, expired,
    business_name: row?.business_name || null,
    expires_at: row?.expires_at || null,
    message: active ? "Suscripción vigente." : expired ? "LICENCIA_VENCIDA" : "Se requiere vincular o renovar SENE VENTAS." });
}

export default {
  // Ejecución automática programada: marca vencidas las licencias al pasar su fecha.
  async scheduled(controller, env, ctx) {
    const now = nowIso();
    ctx.waitUntil(
      env.DB.prepare(
        "UPDATE businesses SET status='expired',updated_at=? WHERE status='active' AND expires_at IS NOT NULL AND expires_at<=?"
      ).bind(now, now).run()
    );
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
        const amount = await getAmount(env.DB);
        return json({ ok: true, amount_cents: amount, currency: "MXN" });
      }
      if (request.method === "POST" && url.pathname === "/requests") return await createRequest(request, env);
      if (request.method === "POST" && url.pathname === "/redeem") return await redeemCode(request, env);
      if (request.method === "POST" && url.pathname === "/account/recovery/request") return await requestAccountRecovery(request, env);
      if (request.method === "POST" && url.pathname === "/account/recovery/complete") return await completeAccountRecovery(request, env);
      if (request.method === "POST" && url.pathname === "/subscription/status") return await subscriptionStatus(request, env);
      return json({ ok: false, error: "RUTA_NO_ENCONTRADA" }, 404);
    } catch (error) {
      return json({ ok: false, error: "ERROR_INTERNO" }, 500);
    }
  }
};
