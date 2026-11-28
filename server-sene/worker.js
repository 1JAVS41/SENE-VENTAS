/**
 * SERVER-SENE Worker API
 * Cloudflare Worker + D1 binding DB.
 * Secrets: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID.
 * Secretos del productor GUARDIÁN (canal del propietario, ver abajo):
 * GUARDIAN_DELIVERY_ENC_KEY, GUARDIAN_DELIVERY_HMAC_KEY.
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

/**
 * Productor de eventos de licencia para SENE GUARDIÁN (canal del propietario).
 *
 * Destino: POST https://sene-guardian-api.nagavs28.workers.dev/v1/guardian/owner-events
 * Contrato (definido por guardian-worker/src/owner-delivery.ts en SeneAdmin):
 * el evento viaja cifrado con AES-GCM (clave de 32 bytes) y firmado con
 * HMAC-SHA-256. Encabezados: x-sene-timestamp (segundos unix, 10 dígitos),
 * x-sene-event-id (UUID) y x-sene-signature (HMAC base64url de
 * "timestamp.eventId.iv.ciphertext"). Cuerpo JSON: { event_id, iv, ciphertext }
 * (todo base64url). El AES-GCM usa el event_id como additionalData.
 *
 * Eventos enviados (solo al canal del propietario; NUNCA a un Muse de cliente):
 * - "activation.requested" / "renewal.requested": al crear la solicitud en
 *   POST /requests, después de avisar por Telegram. payment_status "pending"
 *   y expires_at (48 h).
 * - "activation.completed" / "renewal.completed": al canjear el código en
 *   POST /redeem, después de guardar la suscripción en D1. payment_status
 *   "paid", valid_from / valid_until (30 días).
 * - "transfer.completed": al transferir la cuenta a otro dispositivo en
 *   POST /account/device/transfer, después de mover businesses.installation_id.
 *   Sin código ni monto: solo folio, negocio, titular, WhatsApp y vigencia.
 *
 * FLUJO EN UN SOLO SENTIDO (regla del propietario): SENE VENTAS solo envía
 * avisos hacia GUARDIÁN; el administrador solo recibe a través de su Muse.
 * Nunca hay flujo del administrador ni del Muse del propietario hacia SENE
 * VENTAS. Este productor hace una única llamada saliente (POST a
 * /v1/guardian/owner-events) y no añade ninguna ruta entrante, ninguna
 * escritura en D1 ni ninguna aprobación, activación o envío de códigos
 * hacia SENE VENTAS.
 *
 * Claves: GUARDIAN_DELIVERY_ENC_KEY y GUARDIAN_DELIVERY_HMAC_KEY son secretos
 * del Worker (base64url, nunca en código). Deben tener EXACTAMENTE los mismos
 * valores que los secretos del mismo nombre en el Worker sene-guardian-api;
 * si no coinciden, GUARDIÁN rechaza el evento y el propietario no lo ve.
 *
 * BEST-EFFORT: si las claves no están configuradas, si GUARDIÁN no responde
 * o si rechaza el evento, esto NUNCA rompe la activación ni la renovación:
 * el envío se registra con console.warn y el flujo comercial sigue igual.
 * La única notificación que sí puede cancelar una solicitud es Telegram
 * (comportamiento previo, sin cambios).
 */
const GUARDIAN_OWNER_EVENTS_URL = "https://sene-guardian-api.nagavs28.workers.dev/v1/guardian/owner-events";
const b64urlEncode = bytes => {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
};
const b64urlDecode = value => {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid_base64url");
  const raw = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(raw, c => c.charCodeAt(0));
};
async function notifyGuardianOwner(env, event) {
  try {
    if (!env.GUARDIAN_DELIVERY_ENC_KEY || !env.GUARDIAN_DELIVERY_HMAC_KEY) {
      console.warn("guardian: delivery no configurado, evento no enviado (" + event.event_type + ")");
      return false;
    }
    const encRaw = b64urlDecode(env.GUARDIAN_DELIVERY_ENC_KEY);
    if (encRaw.byteLength !== 32) throw new Error("invalid_enc_key");
    const hmacRaw = b64urlDecode(env.GUARDIAN_DELIVERY_HMAC_KEY);
    if (hmacRaw.byteLength < 32) throw new Error("invalid_hmac_key");
    const aesKey = await crypto.subtle.importKey("raw", encRaw, "AES-GCM", false, ["encrypt"]);
    const hmacKey = await crypto.subtle.importKey("raw", hmacRaw, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    const eventId = crypto.randomUUID();
    const timestamp = String(Math.floor(Date.now() / 1000));
    const plaintext = new TextEncoder().encode(JSON.stringify({ ...event, event_id: eventId }));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
      { name: "AES-GCM", iv, additionalData: new TextEncoder().encode(eventId), tagLength: 128 },
      aesKey, plaintext));
    const ivB64 = b64urlEncode(iv);
    const ctB64 = b64urlEncode(ciphertext);
    const canonical = [timestamp, eventId, ivB64, ctB64].join(".");
    const signature = b64urlEncode(new Uint8Array(await crypto.subtle.sign(
      "HMAC", hmacKey, new TextEncoder().encode(canonical))));
    const response = await fetch(env.GUARDIAN_OWNER_EVENTS_URL || GUARDIAN_OWNER_EVENTS_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-sene-timestamp": timestamp,
        "x-sene-event-id": eventId,
        "x-sene-signature": signature
      },
      body: JSON.stringify({ event_id: eventId, iv: ivB64, ciphertext: ctB64 }),
      signal: AbortSignal.timeout(5000)
    });
    if (!response.ok) {
      console.warn("guardian: evento rechazado (" + event.event_type + "): HTTP " + response.status);
      return false;
    }
    return true;
  } catch (error) {
    // Best-effort: un fallo del productor nunca rompe el flujo comercial.
    console.warn("guardian: no se pudo enviar evento (" + event.event_type + "): " + (error?.message || error));
    return false;
  }
}

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

  // Productor GUARDIÁN (best-effort): avisa al Muse del propietario de la
  // solicitud pendiente. Si falla, la solicitud sigue creada y vigente.
  await notifyGuardianOwner(env, {
    event_type: type + ".requested",
    installation_id: installationId,
    folio,
    request_type: type,
    created_at: createdAt,
    amount_cents: amount,
    currency: "MXN",
    business_name: businessName,
    holder_name: holder,
    whatsapp: phone,
    code,
    payment_status: "pending",
    expires_at: expiresAt
  });

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
           ar.request_type, ar.status AS request_status, ar.expires_at AS request_expires,
           ar.amount_cents, ar.created_at, b.installation_id,
           b.business_name, b.holder_name, b.whatsapp,
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

  // Productor GUARDIÁN (best-effort): avisa al Muse del propietario de la
  // licencia activada/renovada. Si falla, la activación ya quedó guardada
  // en D1 y la respuesta al cliente no cambia.
  await notifyGuardianOwner(env, {
    event_type: row.request_type + ".completed",
    installation_id: row.installation_id,
    folio: row.folio,
    request_type: row.request_type,
    created_at: row.created_at,
    amount_cents: row.amount_cents,
    currency: "MXN",
    business_name: row.business_name,
    holder_name: row.holder_name,
    whatsapp: String(row.whatsapp || "").replace(/\D/g, ""),
    code,
    payment_status: "paid",
    valid_from: starts.toISOString(),
    valid_until: expiresAt
  });

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

/**
 * Traspaso de cuenta por inicio de sesión ("YA SOY CLIENTE SENE").
 *
 * La app ya llama a este endpoint con el folio y el código de activación
 * ORIGINALES y el installation_id del teléfono nuevo. El código original
 * es de un solo uso y está atado al primer teléfono, así que aquí NO se
 * exige solicitud pendiente ni código sin consumir: la autorización es el
 * folio + el hash del código original, aunque ya esté consumido.
 *
 * Espejo de completeAccountRecovery, sin Telegram y sin tabla nueva:
 * mueve businesses.installation_id al dispositivo nuevo y conserva el
 * estado y la fecha de vencimiento tal cual. Si el negocio nunca se
 * activó (status "pending"), el traspaso solo rebindea la activación
 * pendiente al dispositivo nuevo.
 */
async function transferAccountDevice(request, env) {
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
           ar.request_type, ar.status AS request_status, ar.expires_at AS request_expires,
           ar.amount_cents, ar.created_at, b.installation_id,
           b.business_name, b.holder_name, b.whatsapp,
           b.status AS business_status, b.expires_at AS business_expires
    FROM activation_requests ar
    JOIN activation_codes ac ON ac.request_id=ar.id
    JOIN businesses b ON b.id=ar.business_id
    WHERE ar.folio=?
  `).bind(folio).first();

  if (!row) return json({ ok: false, error: "FOLIO_NO_ENCONTRADO" }, 404);
  if (row.business_status === "blocked") {
    return json({ ok: false, error: "NEGOCIO_BLOQUEADO" }, 403);
  }
  if (row.invalidated_at) return json({ ok: false, error: "CODIGO_INVALIDADO" }, 409);
  if (row.locked_at || row.attempt_count >= 5) return json({ ok: false, error: "CODIGO_BLOQUEADO" }, 429);

  // El código original autoriza el traspaso aunque ya esté consumido:
  // se verifica su hash sin exigir solicitud pendiente ni código sin usar.
  const suppliedHash = await sha256(code);
  if (suppliedHash !== row.code_hash) {
    await db.prepare("UPDATE activation_codes SET attempt_count=attempt_count+1, locked_at=CASE WHEN attempt_count+1>=5 THEN ? ELSE locked_at END WHERE id=?")
      .bind(nowIso(), row.code_id).run();
    return json({ ok: false, error: "CODIGO_INCORRECTO" }, 401);
  }

  const oldInstallationId = row.installation_id;
  if (installationId === oldInstallationId) {
    return json({ ok: true, status: "already_linked",
      business_name: row.business_name, expires_at: row.business_expires || null });
  }

  const deviceOwner = await db.prepare(
    "SELECT business_name FROM businesses WHERE installation_id=? AND id<>? LIMIT 1"
  ).bind(installationId, row.business_id).first();
  if (deviceOwner) {
    return json({ ok: false, error: "DISPOSITIVO_OCUPADO", message: "Este dispositivo ya está vinculado a otro negocio." }, 409);
  }

  const transferredAt = nowIso();
  let result;
  try {
    result = await db.prepare(
      "UPDATE businesses SET installation_id=?,updated_at=? WHERE id=? AND installation_id=?"
    ).bind(installationId, transferredAt, row.business_id, oldInstallationId).run();
  } catch (_) {
    return json({ ok: false, error: "NO_SE_PUDO_TRANSFERIR" }, 409);
  }
  if (!result?.meta || result.meta.changes !== 1) {
    return json({ ok: false, error: "NO_SE_PUDO_TRANSFERIR" }, 409);
  }

  // Productor GUARDIÁN (best-effort): avisa al Muse del propietario del
  // traspaso. Sin código ni monto. Si falla, el traspaso ya quedó guardado
  // en D1 y la respuesta al cliente no cambia.
  await notifyGuardianOwner(env, {
    event_type: "transfer.completed",
    installation_id: installationId,
    old_installation_id: oldInstallationId,
    folio,
    business_name: row.business_name,
    holder_name: row.holder_name,
    whatsapp: String(row.whatsapp || "").replace(/\D/g, ""),
    created_at: transferredAt,
    valid_until: row.business_expires || null
  });

  if (row.business_status === "pending") {
    const pendingActivation = await db.prepare(
      "SELECT folio FROM activation_requests WHERE business_id=? AND status='pending' AND expires_at>? ORDER BY created_at DESC LIMIT 1"
    ).bind(row.business_id, nowIso()).first();
    return json({ ok: true, status: "activation_device_rebound",
      activation_folio: pendingActivation?.folio || null });
  }
  return json({ ok: true, status: "transferred",
    business_name: row.business_name, expires_at: row.business_expires || null });
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
      if (request.method === "POST" && url.pathname === "/account/device/transfer") return await transferAccountDevice(request, env);
      if (request.method === "POST" && url.pathname === "/subscription/status") return await subscriptionStatus(request, env);
      return json({ ok: false, error: "RUTA_NO_ENCONTRADA" }, 404);
    } catch (error) {
      return json({ ok: false, error: "ERROR_INTERNO" }, 500);
    }
  }
};

