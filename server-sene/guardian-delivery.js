/**
 * Private SERVER-SENE -> SENE GUARDIÁN delivery helper.
 *
 * Isolated utility only: it is NOT wired into worker.js or production routes.
 * Required dedicated secrets:
 * - GUARDIAN_DELIVERY_URL: HTTPS receiver URL
 * - GUARDIAN_DELIVERY_ENC_KEY: base64 32-byte AES-256-GCM key
 * - GUARDIAN_DELIVERY_HMAC_KEY: base64 key, at least 32 random bytes
 *
 * Never log the event, plaintext, code, ciphertext or authorization material.
 */
const encoder = new TextEncoder();

function decodeBase64(value) {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(normalized);
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
function encodeBase64(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
async function importAes(value) {
  const bytes = decodeBase64(value);
  if (bytes.byteLength !== 32) throw new Error("invalid_guardian_encryption_key");
  return crypto.subtle.importKey("raw", bytes, "AES-GCM", false, ["encrypt"]);
}
async function importHmac(value) {
  const bytes = decodeBase64(value);
  if (bytes.byteLength < 32) throw new Error("invalid_guardian_signing_key");
  return crypto.subtle.importKey("raw", bytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
}
async function sign(key, value) {
  return encodeBase64(new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value))));
}

/**
 * Send a single owner-only request event. Caller must invoke only after the
 * request/payment rows are committed and preserve the existing Telegram flow.
 */
export async function deliverGuardianOwnerEvent(env, event) {
  if (!env.GUARDIAN_DELIVERY_URL || !env.GUARDIAN_DELIVERY_ENC_KEY ||
      !env.GUARDIAN_DELIVERY_HMAC_KEY) {
    throw new Error("guardian_delivery_not_configured");
  }
  const endpoint = new URL(env.GUARDIAN_DELIVERY_URL);
  if (endpoint.protocol !== "https:") throw new Error("guardian_delivery_https_required");
  if (!event || !/^[0-9a-f-]{36}$/i.test(event.event_id || "") ||
      !["activation.requested", "renewal.requested"].includes(event.event_type) ||
      typeof event.folio !== "string" || typeof event.code !== "string" ||
      !/^[A-HJ-NP-Z2-9]{10}$/.test(event.code)) {
    throw new Error("invalid_guardian_owner_event");
  }

  const aesKey = await importAes(env.GUARDIAN_DELIVERY_ENC_KEY);
  const hmacKey = await importHmac(env.GUARDIAN_DELIVERY_HMAC_KEY);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plaintext = encoder.encode(JSON.stringify(event));
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: encoder.encode(event.event_id), tagLength: 128 },
    aesKey, plaintext
  ));
  const timestamp = String(Math.floor(Date.now() / 1000));
  const ivText = encodeBase64(iv);
  const ciphertextText = encodeBase64(ciphertext);
  const canonical = [timestamp, event.event_id, ivText, ciphertextText].join(".");
  const signature = await sign(hmacKey, canonical);
  const response = await fetch(endpoint.toString(), {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-sene-timestamp": timestamp,
      "x-sene-event-id": event.event_id,
      "x-sene-signature": signature
    },
    body: JSON.stringify({ event_id: event.event_id, iv: ivText, ciphertext: ciphertextText })
  });
  // Do not include response body in errors: receiver responses must not echo secrets.
  if (!response.ok) throw new Error("guardian_delivery_failed_" + response.status);
  return { delivered: true, eventId: event.event_id };
}
