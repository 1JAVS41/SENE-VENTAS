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

  if (type === "activation" && business) {
    return json({ ok: false, error: "NOMBRE_EN_USO", message: "NOMBRE EN USO. ELIGE OTRO NOMBRE." }, 409);
  }
  if (type === "renewal" && !business) {
    return json({ ok: false, error: "NEGOCIO_NO_ENCONTRADO" }, 404);
  }
  if (type === "renewal" && business.installation_id && business.installation_id !== installationId) {
    return json({ ok: false, error: "DISPOSITIVO_NO_AUTORIZADO" }, 403);
  }
  if (type === "renewal" && business.status === "blocked") {
    return json({ ok: false, error: "NEGOCIO_BLOQUEADO" }, 403);
  }

  if (business) {
    const pending = await db.prepare(
      "SELECT id,folio,expires_at,amount_cents FROM activation_requests WHERE business_id=? AND status='pending' ORDER BY created_at DESC LIMIT 1"
    ).bind(business.id).first();
    if (pending && new Date(pending.expires_at).getTime() > Date.now()) {
      return json({ ok: false, error: "SOLICITUD_ACTIVA", folio: pending.folio, amount_cents: pending.amount_cents, expires_at: pending.expires_at }, 409);
    }
    if (pending) {
      await db.batch([
        db.prepare("UPDATE activation_requests SET status='expired' WHERE id=? AND status='pending'").bind(pending.id),
        db.prepare("UPDATE activation_codes SET invalidated_at=? WHERE request_id=? AND consumed_at IS NULL AND invalidated_at IS NULL").bind(nowIso(), pending.id)
      ]);
    }
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
    await db.prepare("UPDATE notifications SET status='failed' WHERE request_id=?")
      .bind(requestId).run();
    return json({ ok: false, error: "NOTIFICACION_TELEGRAM_FALLO", folio }, 503);
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
           ar.request_type, ar.status AS request_status, b.installation_id,
           b.status AS business_status, b.expires_at AS business_expires
    FROM activation_requests ar
    JOIN activation_codes ac ON ac.request_id=ar.id
    JOIN businesses b ON b.id=ar.business_id
    WHERE ar.folio=?
  `).bind(folio).first();

  if (!row) return json({ ok: false, error: "FOLIO_NO_ENCONTRADO" }, 404);
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
    await db.batch([
      db.prepare("UPDATE activation_codes SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND invalidated_at IS NULL")
        .bind(activatedAt, row.code_id),
      db.prepare("UPDATE activation_requests SET status='completed' WHERE id=?")
        .bind(row.request_id),
      db.prepare("UPDATE payments SET verification_status='verified',verified_at=? WHERE request_id=?")
        .bind(activatedAt, row.request_id),
      db.prepare("UPDATE businesses SET status='active',activated_at=COALESCE(activated_at,?),expires_at=?,updated_at=? WHERE id=?")
        .bind(activatedAt, expiresAt, activatedAt, row.business_id),
      db.prepare("INSERT INTO subscriptions (id,business_id,request_id,starts_at,expires_at,created_at) VALUES (?,?,?,?,?,?)")
        .bind(crypto.randomUUID(), row.business_id, row.request_id, starts.toISOString(), expiresAt, activatedAt)
    ]);
  } catch (_) {
    return json({ ok: false, error: "NO_SE_PUDO_ACTIVAR" }, 500);
  }
  return json({ ok: true, status: "active", activated_at: activatedAt, expires_at: expiresAt });
}

async function subscriptionStatus(request, env) {
  const body = await request.json();
  const installationId = typeof body.installation_id === "string" ? body.installation_id.trim() : "";
  if (!validText(installationId, 20, 100)) return json({ ok: false, error: "INSTALACION_INVALIDA" }, 400);
  const row = await env.DB.prepare(
    "SELECT business_name,status,expires_at FROM businesses WHERE installation_id=? LIMIT 1"
  ).bind(installationId).first();
  const active = !!row && row.status === "active" && !!row.expires_at &&
    new Date(row.expires_at).getTime() > Date.now();
  return json({ ok: true, active, business_name: row?.business_name || null,
    expires_at: row?.expires_at || null,
    message: active ? "Suscripción vigente." : "Se requiere vincular o renovar SENE VENTAS." });
}

export default {
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
      if (request.method === "POST" && url.pathname === "/subscription/status") return await subscriptionStatus(request, env);
      return json({ ok: false, error: "RUTA_NO_ENCONTRADA" }, 404);
    } catch (error) {
      return json({ ok: false, error: "ERROR_INTERNO" }, 500);
    }
  }
};
