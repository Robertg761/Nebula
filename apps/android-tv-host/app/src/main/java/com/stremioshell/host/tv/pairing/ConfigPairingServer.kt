package com.stremioshell.host.tv.pairing

import com.stremioshell.host.tv.data.addon.AddonList
import fi.iki.elonen.NanoHTTPD
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
 *
 * The token gate cannot be the only defence, because it runs after a connection already has a
 * thread: NanoHTTPD's own runner starts one per accepted socket, without limit, so any host on the
 * LAN could open connections until the TV ran out of threads or memory while the QR was up. The
 * bounded runner installed below caps that at [MAX_CONNECTIONS] and closes the rest on arrival.
 */
class ConfigPairingServer(
  private val token: String,
  private val onConfig: (PairingSubmission) -> PairingApplyResult,
) : NanoHTTPD(0) {

  private val guard = PairingTokenGuard(token)
  private val submissionState = AtomicReference(SubmissionState.Available)
  private val connections = BoundedAsyncRunner(MAX_CONNECTIONS)

  init {
    setAsyncRunner(connections)
  }

  /** Names this server's worker threads, so a test can see that the cap is real. */
  internal val connectionThreadPrefix: String get() = connections.threadNamePrefix

  override fun serve(session: IHTTPSession): Response {
    val isSubmit = session.method == Method.POST && session.uri == "/config"
    if (!guard.isAuthorized(session.parameters[TOKEN_FIELD]?.firstOrNull())) return forbidden()
    if (submissionState.get() == SubmissionState.Consumed) return forbidden()
    return when {
      isSubmit -> handleSubmit(session)
      session.method == Method.GET && session.uri == "/" -> html(formPage())
      else -> newFixedLengthResponse(
        Response.Status.NOT_FOUND,
        "text/plain; charset=utf-8",
        "Not found.",
      ).also {
        it.addHeader("Cache-Control", "no-store")
        // A tokened POST to the wrong path is also rejected before its body is consumed.
        if (session.method == Method.POST) it.closeConnection(true)
      }
    }
  }

  private fun handleSubmit(session: IHTTPSession): Response {
    val contentLength = session.headers["content-length"]?.toLongOrNull()
      ?: return newFixedLengthResponse(
        Response.Status.LENGTH_REQUIRED,
        "text/plain; charset=utf-8",
        "The request length is missing.",
      ).also {
        it.addHeader("Cache-Control", "no-store")
        it.closeConnection(true)
      }
    if (contentLength !in 0..MAX_FORM_BYTES) {
      return newFixedLengthResponse(
        Response.Status.PAYLOAD_TOO_LARGE,
        "text/plain; charset=utf-8",
        "That form is too large.",
      ).also {
        it.addHeader("Cache-Control", "no-store")
        it.closeConnection(true)
      }
    }
    // Claim the one-shot token before parsing. A second authenticated request can therefore never
    // be parsed or applied concurrently. Validation/storage failures release it so the same phone
    // can correct a typo without rescanning.
    if (!submissionState.compareAndSet(SubmissionState.Available, SubmissionState.Applying)) {
      return forbidden()
    }
    val parsed = runCatching { session.parseBody(HashMap()) }
    if (parsed.isFailure) {
      submissionState.compareAndSet(SubmissionState.Applying, SubmissionState.Available)
      return badRequest("That form could not be read. Please try again.")
    }
    val rawAddons = session.parameters["addon"]?.firstOrNull()
    // The single gate on the addon box, and the reason nothing below has to second-guess it: it
    // rejects any box that would lose a line to sanitising - unusable, duplicated, or more than
    // the cap - so a box that gets past here always arrives as one URL per line, all of them
    // saved, and the confirmation's count is the number the viewer typed. Reported before
    // anything is applied, too: saving the key alone while quietly discarding the URLs would look
    // like a success the viewer then has to debug on the TV.
    PairingSubmission.addonInputError(rawAddons)?.let { error ->
      submissionState.compareAndSet(SubmissionState.Applying, SubmissionState.Available)
      return html(formPage(error = error))
    }
    val submission = PairingSubmission.of(
      rawTmdbKey = session.parameters["tmdb"]?.firstOrNull(),
      rawAddonUrls = rawAddons,
    )
    if (submission.isEmpty) {
      submissionState.compareAndSet(SubmissionState.Applying, SubmissionState.Available)
      return html(formPage(error = "Enter at least one value."))
    }
    return when (val result = runCatching { onConfig(submission) }.getOrElse {
      PairingApplyResult.Failed("The TV could not save those settings. Please try again.")
    }) {
      is PairingApplyResult.Saved -> {
        submissionState.set(SubmissionState.Consumed)
        html(donePage(result.receipt))
      }
      is PairingApplyResult.Failed -> {
        submissionState.compareAndSet(SubmissionState.Applying, SubmissionState.Available)
        html(formPage(error = result.message))
      }
    }
  }

  private fun html(body: String): Response =
    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
      .also { it.addHeader("Cache-Control", "no-store") }

  private fun forbidden(): Response =
    newFixedLengthResponse(
      Response.Status.FORBIDDEN,
      "text/plain; charset=utf-8",
      "Forbidden. Scan the code shown on your TV to open this page.",
    ).also {
      it.addHeader("Cache-Control", "no-store")
      // An unauthorized or already-consumed POST is deliberately rejected before parseBody reads
      // its credential-bearing payload. Close that connection so unread form bytes cannot be
      // interpreted as another request on NanoHTTPD's keep-alive socket.
      it.closeConnection(true)
    }

  private fun badRequest(message: String): Response =
    newFixedLengthResponse(
      Response.Status.BAD_REQUEST,
      "text/plain; charset=utf-8",
      message,
    ).also {
      it.addHeader("Cache-Control", "no-store")
      it.closeConnection(true)
    }

  private fun formPage(error: String? = null): String {
    val errorHtml = if (error != null) {
      "<p class=\"err\" id=\"form-error\" role=\"alert\" aria-live=\"assertive\">" +
        "${escape(error)}</p>"
    } else {
      ""
    }
    val errorDescription = if (error != null) " form-error" else ""
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
      network. This page is not encrypted, so use it only on a trusted private network. Leave a box
      empty to keep what the TV already has.</p>
      $errorHtml
      <form method="POST" action="/config?$TOKEN_FIELD=${escape(token)}">
        <label for="tmdb">TMDB API key
          <span class="hint" id="tmdb-hint">themoviedb.org &rsaquo; Settings &rsaquo; API</span></label>
        <input id="tmdb" name="tmdb" aria-describedby="tmdb-hint$errorDescription"
               autocomplete="off" autocapitalize="off" spellcheck="false"
               placeholder="Leave empty to keep current key">
        <label for="addon">Stream addon manifest URLs
          <span class="hint" id="addon-hint">one per line, up to ${AddonList.MAX_ADDONS} - e.g. your Comet instance, with your Real-Debrid key</span></label>
        <textarea id="addon" name="addon" rows="4"
               aria-describedby="addon-hint$errorDescription"
               autocomplete="off" autocapitalize="off" spellcheck="false"
               placeholder="Leave empty to keep the addons the TV already has"></textarea>
        <button type="submit">Save to TV</button>
      </form>
      </main></body></html>
    """.trimIndent()
  }

  private fun donePage(receipt: PairingReceipt): String {
    val tmdbState = if (receipt.tmdbKeyChanged) "updated" else "unchanged"
    // A count, never the URLs themselves: this page is served over the same cleartext HTTP the
    // form is, and the URLs carry the viewer's Real-Debrid key.
    val addonState = when {
      !receipt.addonUrlsChanged -> "unchanged"
      receipt.addonCount == 1 -> "1 saved"
      else -> "${receipt.addonCount} saved"
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
    /** Query-string key carried by both the QR URL and the form action. */
    const val TOKEN_FIELD = "t"
    const val MAX_FORM_BYTES = 32L * 1024L

    /**
     * How many connections this server will hold at once.
     *
     * One phone browser opens a handful in parallel (the page, a favicon, a speculative preconnect)
     * and only one of them ever posts, so this is generous for the only client that is supposed to
     * be here - while a flood from anywhere else costs the TV eight threads instead of as many as
     * it can allocate. Rejected sockets are closed immediately rather than queued, so a stalled
     * attacker cannot make legitimate connections wait behind a backlog either; NanoHTTPD's own
     * five-second read timeout returns each slot soon enough that the phone's retry gets in.
     */
    const val MAX_CONNECTIONS = 8

    private val serverIndex = AtomicInteger()
  }

  private enum class SubmissionState { Available, Applying, Consumed }

  /**
   * A [NanoHTTPD.AsyncRunner] with a ceiling, replacing the default one thread per accepted socket.
   *
   * Everything else in this class defends the viewer's credentials, and all of it runs after the
   * connection has already been given a thread. On a 2GB TV box that ordering is the whole attack:
   * a peer that opens sockets and never speaks costs nothing to sustain and one thread each to
   * hold, and the server sits on the LAN for as long as the QR is on screen.
   */
  private class BoundedAsyncRunner(maxConnections: Int) : NanoHTTPD.AsyncRunner {
    val threadNamePrefix = "nebula-pairing-${serverIndex.incrementAndGet()}-"

    private val threadIndex = AtomicInteger()
    private val running = Collections.synchronizedList(mutableListOf<NanoHTTPD.ClientHandler>())

    /**
     * No queue at all - a [SynchronousQueue] hands off to a waiting thread or rejects on the spot.
     * A bounded queue would only delay the same exhaustion and make the phone wait behind sockets
     * that will never send a byte.
     */
    private val executor = ThreadPoolExecutor(
      0,
      maxConnections,
      IDLE_THREAD_SECONDS,
      TimeUnit.SECONDS,
      SynchronousQueue(),
    ) { runnable ->
      Thread(runnable, "$threadNamePrefix${threadIndex.incrementAndGet()}").apply {
        // Daemon, like NanoHTTPD's own runner: a pairing screen left open must never be the
        // reason the process refuses to exit.
        isDaemon = true
      }
    }

    override fun exec(handler: NanoHTTPD.ClientHandler) {
      running.add(handler)
      try {
        executor.execute(handler)
      } catch (_: RejectedExecutionException) {
        // At the ceiling, or already stopped. Closing the socket is the honest answer: the peer
        // learns immediately instead of holding an accepted connection that nothing will serve.
        running.remove(handler)
        handler.close()
      }
    }

    /** Called by every handler's own finally block, including the ones that threw. */
    override fun closed(handler: NanoHTTPD.ClientHandler) {
      running.remove(handler)
    }

    override fun closeAll() {
      // Sockets first, so the threads blocked reading them are already unwinding when they are
      // interrupted. This runner is not reused: stop() ends the session, and the next pairing
      // attempt builds a new server with a new token.
      val snapshot = synchronized(running) { ArrayList(running) }
      snapshot.forEach { runCatching { it.close() } }
      executor.shutdownNow()
    }

    private companion object {
      const val IDLE_THREAD_SECONDS = 10L
    }
  }
}
