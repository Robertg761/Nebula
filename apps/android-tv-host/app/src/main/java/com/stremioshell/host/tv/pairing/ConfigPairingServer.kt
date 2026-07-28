package com.stremioshell.host.tv.pairing

import com.stremioshell.host.tv.data.addon.AddonList
import fi.iki.elonen.NanoHTTPD

/**
 * Tiny LAN-only web server shown during phone pairing. The phone opens the
 * page, submits the TMDB key and its stream addon URLs from its own keyboard,
 * and the values are handed back via [onConfig] (called on a server thread).
 *
 * Runs only while the pairing screen is visible; bound to port 0 so the OS
 * assigns a free port, read back from [listeningPort] after start().
 *
 * Security: this is cleartext HTTP reachable by every host on the LAN, so
 * every request - GET and POST alike - must carry [token], which is only ever
 * published in the QR code on the user's own screen. Requests without it get a
 * 403 and nothing else. The served form is likewise never pre-filled with the
 * stored TMDB key or addon URLs (those embed a Real-Debrid token): the page is
 * write-only, so even a leaked token cannot read the existing config back out.
 * The confirmation page obeys the same rule - it reports how many addons were
 * saved and never which.
 */
class ConfigPairingServer(
  private val token: String,
  private val onConfig: (PairingSubmission) -> Unit,
) : NanoHTTPD(0) {

  private val guard = PairingTokenGuard(token)

  override fun serve(session: IHTTPSession): Response {
    val isSubmit = session.method == Method.POST && session.uri == "/config"
    // The token rides in a hidden field on POST, so the body has to be decoded
    // before it can be checked. It is a handful of bytes from a form we served.
    if (isSubmit) runCatching { session.parseBody(HashMap()) }
    if (!guard.isAuthorized(session.parameters[TOKEN_FIELD]?.firstOrNull())) return forbidden()
    return if (isSubmit) handleSubmit(session) else html(formPage())
  }

  private fun handleSubmit(session: IHTTPSession): Response {
    val rawAddons = session.parameters["addon"]?.firstOrNull()
    val submission = PairingSubmission.of(
      rawTmdbKey = session.parameters["tmdb"]?.firstOrNull(),
      rawAddonUrls = rawAddons,
    )
    // Typed something into the addon box and none of it survived sanitising. Reported rather than
    // ignored, and reported before anything is applied: saving the key alone while quietly
    // discarding the URLs would look like a success the viewer then has to debug on the TV.
    if (!rawAddons.isNullOrBlank() && submission.addonUrls == null) {
      return html(formPage(error = "No usable addon link in that box. Paste the manifest URL."))
    }
    if (submission.isEmpty) {
      return html(formPage(error = "Enter at least one value."))
    }
    onConfig(submission)
    return html(donePage(submission))
  }

  private fun html(body: String): Response =
    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
      .also { it.addHeader("Cache-Control", "no-store") }

  private fun forbidden(): Response =
    newFixedLengthResponse(
      Response.Status.FORBIDDEN,
      "text/plain; charset=utf-8",
      "Forbidden. Scan the code shown on your TV to open this page.",
    ).also { it.addHeader("Cache-Control", "no-store") }

  private fun formPage(error: String? = null): String {
    val errorHtml = if (error != null) "<p class=\"err\">${escape(error)}</p>" else ""
    return """
      <!doctype html><html><head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <meta name="referrer" content="no-referrer">
      <title>Set up Nebula</title>
      <style>
        :root { color-scheme: dark; }
        * { box-sizing: border-box; }
        body { margin:0; background:#06060F; color:#F2F0FF;
               font-family:"Outfit","Segoe UI",system-ui,sans-serif; display:flex; justify-content:center; }
        main { width:100%; max-width:520px; padding:32px 20px 60px; }
        h1 { font-size:24px; margin:0 0 4px; letter-spacing:0.2px; }
        p.sub { color:#A9A4C7; margin:0 0 26px; font-size:14px; line-height:1.5; }
        label { display:block; font-weight:600; margin:20px 0 8px; font-size:14px; color:#F2F0FF; }
        .hint { color:#A9A4C7; font-weight:400; font-size:12px; }
        input, textarea { width:100%; background:#1E1E3C; color:#F2F0FF;
                border:1px solid #2C2C52; border-radius:12px; padding:14px 16px; font-size:16px;
                font-family:inherit; transition:border-color .15s ease; }
        input:focus, textarea:focus { outline:none; border-color:#8B6CFF;
                box-shadow:0 0 0 3px rgba(139,108,255,0.25); }
        input::placeholder, textarea::placeholder { color:#6F6A93; }
        textarea { min-height:96px; resize:vertical; }
        button { margin-top:28px; width:100%; background:#8B6CFF; color:#fff; border:0;
                 border-radius:22px; padding:16px; font-size:17px; font-weight:600;
                 font-family:inherit; }
        button:focus, button:active { outline:none; box-shadow:0 0 0 3px rgba(167,139,255,0.4); }
        .err { background:#40202e; color:#FF6B7A; padding:12px 16px; border-radius:12px; font-size:14px; }
      </style></head><body><main>
      <h1>Set up Nebula</h1>
      <p class="sub">Paste your keys here, then tap Save. They go straight to your TV over your home
      network. Leave a box empty to keep what the TV already has.</p>
      $errorHtml
      <form method="POST" action="/config">
        <input type="hidden" name="$TOKEN_FIELD" value="${escape(token)}">
        <label>TMDB API key <span class="hint">themoviedb.org &rsaquo; Settings &rsaquo; API</span></label>
        <input name="tmdb" autocomplete="off" autocapitalize="off" spellcheck="false"
               placeholder="Leave empty to keep current key">
        <label>Stream addon manifest URLs
          <span class="hint">one per line, up to ${AddonList.MAX_ADDONS} - e.g. your Comet instance, with your Real-Debrid key</span></label>
        <textarea name="addon" rows="4" autocomplete="off" autocapitalize="off" spellcheck="false"
               placeholder="Leave empty to keep the addons the TV already has"></textarea>
        <button type="submit">Save to TV</button>
      </form>
      </main></body></html>
    """.trimIndent()
  }

  private fun donePage(submission: PairingSubmission): String {
    val tmdbState = if (submission.tmdbKey != null) "updated" else "unchanged"
    // A count, never the URLs themselves: this page is served over the same cleartext HTTP the
    // form is, and the URLs carry the viewer's Real-Debrid key.
    val addons = submission.addonUrls
    val addonState = when {
      addons == null -> "unchanged"
      addons.size == 1 -> "1 saved"
      else -> "${addons.size} saved"
    }
    return """
      <!doctype html><html><head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <meta name="referrer" content="no-referrer">
      <title>Saved</title>
      <style>
        :root { color-scheme: dark; }
        body { margin:0; background:#06060F; color:#F2F0FF;
               font-family:"Outfit","Segoe UI",system-ui,sans-serif;
               display:flex; align-items:center; justify-content:center; height:100vh; text-align:center; }
        div { padding:24px; }
        h1 { color:#A78BFF; font-size:24px; }
        ul { list-style:none; padding:0; color:#A9A4C7; font-size:14px; }
        li { margin:6px 0; }
        p { color:#6F6A93; font-size:13px; }
      </style></head><body>
      <div><h1>Saved to your TV</h1>
      <ul>
        <li>TMDB API key: $tmdbState</li>
        <li>Stream addons: $addonState</li>
      </ul>
      <p>You can close this page and pick something to watch.</p></div>
      </body></html>
    """.trimIndent()
  }

  private fun escape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

  companion object {
    /** Query-string key in the QR URL and hidden form field name. */
    const val TOKEN_FIELD = "t"
  }
}
