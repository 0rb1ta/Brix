package app.brix.streaming.overlay

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray

/**
 * JS bridge: the donation widget (e.g. Donation Alerts) renders an alert into a
 * hidden WebView. Two cooperating JS-side scripts report a new alert to
 * [onAlert], which hands it to [OverlayController.show] so it is rendered
 * in-stream (and its audio routed per the configured switches):
 *
 * 1. [SOUND_HOOK_JS] wraps `window.soundManager.createSound`, the call the
 *    widget is confirmed (via console logging) to make on every donation, and
 *    records the sound URL — it does not fire [onAlert] itself. Installed at
 *    document-start (before the widget's own scripts run) via
 *    [WebViewCompat.addDocumentStartJavaScript] so the wrap is in place
 *    before `soundManager` is even created. Records EVERY call in a short
 *    window as a list, not just the last one — a single donation can trigger
 *    more than one sound (chat notification chime, then a paid TTS readout of
 *    the message, then possibly a recorded voice message), and picking only
 *    the latest silently dropped the earlier ones.
 * 2. [ALERT_OBSERVER_JS], a MutationObserver on `<img>` add/src-change, is the
 *    sole trigger that fires [onAlert] — it pairs the image with whichever
 *    sound URLs [SOUND_HOOK_JS] recorded in the last few seconds, and also
 *    makes a best-effort attempt to scrape the alert's text (and a username
 *    guess) from the DOM near the image. Two independent DOM/JS-side triggers
 *    both calling [onAlert] used to race — whichever fired first for a given
 *    image "won", which was almost always this observer (a DOM mutation is
 *    near-instant; the sound hook has no reliable way to know when the image
 *    will appear) and it never had an audio URL on its own — so alerts were
 *    consistently silent. Single owner instead: this observer is the only
 *    thing that calls [onAlert].
 */
class OverlayJsBridge(
    private val controller: OverlayController,
) {
    @Volatile
    var enabled = true

    /** Хост страницы виджета, снимается при её загрузке. */
    @Volatile
    private var pageHost: String? = null

    /**
     * Пускать ли эту ссылку в эфир.
     *
     * `addJavascriptInterface` не различает origin: мост виден и самой странице
     * виджета, и любому её стороннему iframe — рекламному, счётчику. А
     * [onAlert] рисует произвольную картинку и играет произвольный звук ПРЯМО
     * В ЭФИР. Полноценно закрыть это можно только переездом на
     * `addWebMessageListener`, где origin задаётся явно, но он требует сменить
     * протокол вызова и переделать оба внедряемых скрипта — на живой,
     * отлаженной в поле фиче это отдельная работа.
     *
     * Пока сужаем не источник вызова, а последствия: медиа обязано лежать на
     * домене самого виджета. Сравнение по двум последним меткам хоста, потому
     * что картинки и звуки часто уезжают на поддомен-CDN
     * (`cdn.donationalerts.com` при странице на `donationalerts.com`), и
     * равенство хостов ломало бы рабочие алерты.
     */
    private fun allowedMediaUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        val page = pageHost ?: return false
        if (host.equals(page, ignoreCase = true)) return true
        fun registrable(h: String) = h.split('.').takeLast(2).joinToString(".").lowercase()
        return registrable(host) == registrable(page)
    }

    @Volatile
    var posX = 0.5f

    @Volatile
    var posY = 0.5f

    @Volatile
    var widthFraction = 0.3f

    @Volatile
    var heightFraction = 0.3f

    @Volatile
    var audioOnDevice = false

    @Volatile
    var audioInStream = false

    @Volatile
    var captionScale = 1f

    @Volatile
    var captionVisible = true

    /** Called from the widget's JS when a new alert image appears.
     *  [audioUrlsJson] is a JSON array of zero or more sound URLs (chat
     *  chime, TTS readout, voice message — whichever the widget actually
     *  played for this alert); [text] is a best-effort scrape of the
     *  donor's message, [username] a best-effort guess at just the name,
     *  and [amount] the amount+currency — all scraped from the DOM near the
     *  alert image. */
    @JavascriptInterface
    fun onAlert(imageUrl: String, audioUrlsJson: String?, durationMs: Long, text: String?, username: String?, amount: String?) {
        android.util.Log.w(
            "Overlay",
            "bridge: onAlert img=$imageUrl audio=$audioUrlsJson dur=$durationMs text=$text username=$username amount=$amount",
        )
        if (!enabled || imageUrl.isBlank()) return
        if (!allowedMediaUrl(imageUrl)) {
            android.util.Log.w("Overlay", "bridge: картинка не с домена виджета, в эфир не пускаем — $imageUrl")
            return
        }
        val media = OverlayController.AlertMedia(
            imageUrl = imageUrl,
            audioUrls = parseAudioUrls(audioUrlsJson).filter(::allowedMediaUrl),
            durationMs = durationMs.coerceAtLeast(1000),
            text = text?.takeIf { it.isNotBlank() },
            username = username?.takeIf { it.isNotBlank() },
            amount = amount?.takeIf { it.isNotBlank() },
            posX = posX,
            posY = posY,
            width = widthFraction,
            height = heightFraction,
            captionScale = captionScale,
            captionVisible = captionVisible,
        )
        controller.show(
            media,
            OverlayController.AudioOptions(
                onDevice = audioOnDevice,
                inStream = audioInStream,
            ),
        )
    }

    /** Called from a follow-up JS check ~3.5s after [onAlert] for any sound
     *  URL that wasn't captured yet — specifically a paid TTS/voice readout,
     *  which is generated server-side and can appear well after the alert
     *  image itself (confirmed: ~2.8s later on a real donation). Plays
     *  through the same audio pipeline as the original alert's clips, just
     *  without re-showing the image/caption. */
    @JavascriptInterface
    fun onLateAudio(audioUrlsJson: String?) {
        val urls = parseAudioUrls(audioUrlsJson).filter(::allowedMediaUrl)
        android.util.Log.w("Overlay", "bridge: onLateAudio audio=$audioUrlsJson")
        if (!enabled || urls.isEmpty()) return
        controller.playAdditionalAudio(
            urls,
            OverlayController.AudioOptions(
                onDevice = audioOnDevice,
                inStream = audioInStream,
            ),
        )
    }

    private fun parseAudioUrls(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Expose the bridge to the page and install both alert triggers. */
    fun installInto(webView: WebView) {
        webView.addJavascriptInterface(this, JS_BRIDGE_NAME)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, SOUND_HOOK_JS, setOf("*"))
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Хост главного фрейма — эталон, с которым сверяется медиа
                // алертов (см. allowedMediaUrl).
                url?.let { pageHost = runCatching { java.net.URI(it).host }.getOrNull() ?: pageHost }
                android.util.Log.d("Overlay", "bridge: page finished $url — injecting observer")
                view?.evaluateJavascript(ALERT_OBSERVER_JS, null)
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    // Fallback for WebView versions without document-start injection —
                    // late, so it only catches donations after the first page load.
                    view?.evaluateJavascript(SOUND_HOOK_JS, null)
                }
            }

            /**
             * PROBE ONLY (no interception): logs every request that looks like
             * audio, then returns null so the page loads it exactly as before.
             *
             * This is the network-level backstop under the JS hooks. The JS
             * side can only see the playback APIs we thought to wrap; every
             * clip that reaches the page at all, whatever API plays it, still
             * has to come down the wire through here. So a clip that shows up
             * in this log but not in the JS log tells us precisely which
             * playback path we are still blind to — and a donation that
             * produces audio with NO line here at all means the sound was
             * never fetched as a resource (browser-synthesised speech), which
             * is the one case no URL-based capture can ever reach.
             *
             * Runs on a WebView background thread, so this must stay cheap and
             * must not touch the controller.
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString()
                if (url != null && AUDIO_URL_RE.containsMatchIn(url)) {
                    android.util.Log.w("Overlay", "probe: audio request $url")
                }
                return null
            }
        }
    }

    companion object {
        const val JS_BRIDGE_NAME = "AndroidOverlayBridge"

        /** Extensions the probe treats as "this request is a sound clip".
         *  Deliberately extension-based rather than MIME-based: the response
         *  (and its Content-Type) is not available at shouldInterceptRequest
         *  time, only the request. */
        private val AUDIO_URL_RE =
            Regex("""\.(mp3|ogg|oga|wav|m4a|aac|opus|weba)(\?|#|$)""", RegexOption.IGNORE_CASE)

        /**
         * Wraps `window.soundManager.createSound` (SoundManager2 API) via a
         * property interceptor on `window.soundManager`, since the widget's
         * script creates that object asynchronously, after this runs.
         */
        private const val SOUND_HOOK_JS = """(function(){
            if (window.__brixSoundHook) return; window.__brixSoundHook = true;
            var real;
            // Records every donation sound URL fired in the last few
            // seconds as a list — it does NOT fire onAlert itself. One
            // donation can fire createSound more than once (chat chime,
            // then a paid TTS readout, then possibly a recorded voice
            // message), and createSound / the alert <img> can appear in
            // either order a few dozen ms apart, so a single "last URL"
            // value both raced with the DOM observer and silently dropped
            // every sound but the most recent. ALERT_OBSERVER_JS is the
            // sole owner that fires onAlert; it just reads whatever's been
            // recorded here recently.
            function wrap(sm){
                if (!sm || sm.__brixWrapped || typeof sm.createSound !== 'function') return sm;
                var orig = sm.createSound;
                sm.createSound = function(opts){
                    try {
                        var url = '';
                        if (opts && typeof opts === 'object') url = opts.url || opts.URL || '';
                        else if (typeof arguments[1] === 'string') url = arguments[1];
                        console.log('[brix] createSound hook url=', url);
                        if (url) {
                            window.__brixSounds = window.__brixSounds || [];
                            window.__brixSounds.push({url: url, at: Date.now()});
                            if (window.__brixSounds.length > 20) window.__brixSounds.shift();
                        }
                        // We already download and replay this URL ourselves
                        // (OverlayController/DonationAudioPlayer) — the widget's
                        // own local playback was previously silently blocked by
                        // the autoplay-gesture policy, which masked this. Now
                        // that it's unblocked (mediaPlaybackRequiresUserGesture
                        // = false, StreamScreen.kt), leaving the widget's copy
                        // audible too doubles the notification. Mute it at the
                        // source, not the capture.
                        if (opts && typeof opts === 'object') opts.volume = 0;
                    } catch (e) { console.log('[brix] hook err', e); }
                    return orig.apply(this, arguments);
                };
                sm.__brixWrapped = true;
                return sm;
            }
            if (window.soundManager) real = wrap(window.soundManager);
            try {
                Object.defineProperty(window, 'soundManager', {
                    configurable: true,
                    get: function(){ return real; },
                    set: function(v){ real = wrap(v); },
                });
            } catch (e) { console.log('[brix] soundManager hook install err', e); }
            // Same capture + mute treatment for plain <audio>/Audio() playback,
            // in case the widget's voice/TTS readout goes through
            // HTMLMediaElement rather than SoundManager2 — SOUND_HOOK_JS only
            // wrapped createSound before, so any such clip left no trace at
            // all. Installed at document-start, so this wraps the prototype
            // before the widget creates any media element.
            try {
                if (window.HTMLMediaElement && HTMLMediaElement.prototype && !HTMLMediaElement.prototype.__brixPlayWrapped) {
                    var origPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function(){
                        try {
                            var url = this.currentSrc || this.src || '';
                            if (url) {
                                console.log('[brix] media play hook url=', url);
                                window.__brixSounds = window.__brixSounds || [];
                                window.__brixSounds.push({url: url, at: Date.now()});
                                if (window.__brixSounds.length > 20) window.__brixSounds.shift();
                            }
                            this.volume = 0;
                        } catch (e) { console.log('[brix] media play hook err', e); }
                        return origPlay.apply(this, arguments);
                    };
                    HTMLMediaElement.prototype.__brixPlayWrapped = true;
                }
            } catch (e) { console.log('[brix] media play hook install err', e); }
            // Diagnostic only — a donation confirmed to have voice readout left no
            // createSound/<audio src> trace at all in the last capture. If DonationAlerts
            // reads it out via the browser's own Web Speech API, there is no downloadable
            // audio to capture (no URL, no buffer) — this just proves or rules that out.
            try {
                if (window.speechSynthesis && window.speechSynthesis.speak && !window.speechSynthesis.__brixWrapped) {
                    var origSpeak = window.speechSynthesis.speak.bind(window.speechSynthesis);
                    window.speechSynthesis.speak = function(utterance){
                        try { console.log('[brix] speechSynthesis.speak text=', utterance && utterance.text); } catch (e2) {}
                        return origSpeak(utterance);
                    };
                    window.speechSynthesis.__brixWrapped = true;
                }
            } catch (e) { console.log('[brix] speechSynthesis hook install err', e); }
            // ---- PROBE (diagnostic only, captures nothing) ----
            // Goal: find out whether the two hooks above are actually enough to
            // see every sound this widget plays, before anything is built on
            // that assumption. Each of these wraps a standard Web API that a
            // widget could use INSTEAD of SoundManager2 / <audio>, logs that it
            // fired, and changes no behaviour whatsoever.
            //
            // Read the resulting capture like this:
            //  - only 'createSound'/'media play' lines  -> the existing hooks
            //    see everything; a generic capture layer is viable.
            //  - a 'probe webaudio' line with no URL    -> the clip was played
            //    from a decoded buffer; recoverable, but we would have to
            //    capture it at fetch time, not at play time.
            //  - 'probe speechSynthesis' with no audio request in logcat
            //    -> browser-synthesised speech, no bytes exist anywhere. This
            //    is the one case that genuinely cannot be captured without
            //    MediaProjection, and it decides whether the donation-specific
            //    path can be retired or has to stay.
            try {
                var AC = window.AudioContext || window.webkitAudioContext;
                if (AC && AC.prototype && !AC.prototype.__brixProbed) {
                    var origDecode = AC.prototype.decodeAudioData;
                    if (origDecode) {
                        AC.prototype.decodeAudioData = function(){
                            try {
                                var buf = arguments[0];
                                console.log('[brix] probe webaudio decodeAudioData bytes=', buf && buf.byteLength);
                            } catch (e) {}
                            return origDecode.apply(this, arguments);
                        };
                    }
                    AC.prototype.__brixProbed = true;
                }
            } catch (e) { console.log('[brix] probe webaudio install err', e); }
            try {
                if (window.AudioBufferSourceNode && AudioBufferSourceNode.prototype
                    && !AudioBufferSourceNode.prototype.__brixProbed) {
                    var origStart = AudioBufferSourceNode.prototype.start;
                    AudioBufferSourceNode.prototype.start = function(){
                        try {
                            var d = this.buffer ? this.buffer.duration : -1;
                            console.log('[brix] probe webaudio source start durationSec=', d);
                        } catch (e) {}
                        return origStart.apply(this, arguments);
                    };
                    AudioBufferSourceNode.prototype.__brixProbed = true;
                }
            } catch (e) { console.log('[brix] probe webaudio source install err', e); }
            // new Audio(url) built but never .play()ed still tells us the URL
            // existed — useful when playback happens through a path we missed.
            try {
                if (window.Audio && !window.Audio.__brixProbed) {
                    var OrigAudio = window.Audio;
                    var Wrapped = function(src){
                        try { console.log('[brix] probe new Audio src=', src || ''); } catch (e) {}
                        return new OrigAudio(src);
                    };
                    Wrapped.prototype = OrigAudio.prototype;
                    Wrapped.__brixProbed = true;
                    window.Audio = Wrapped;
                }
            } catch (e) { console.log('[brix] probe Audio ctor install err', e); }
            console.log('[brix] soundManager hook installed');
        })();"""

        /** MutationObserver that reports new/updated alert <img>, paired with
         *  whichever sound URLs [SOUND_HOOK_JS] recorded recently and a
         *  best-effort scrape of the alert's text/username from the DOM. */
        private const val ALERT_OBSERVER_JS = """(function(){
            if (window.__brixObs) return; window.__brixObs = true;
            var last = {};
            function collectAudioUrls(){
                var now = Date.now();
                var fresh = (window.__brixSounds || []).filter(function(s){ return now - s.at < 3000; });
                window.__brixSounds = [];
                // SoundManager2's HTML5 backend creates a real <audio> element
                // internally and calls .play() on it — both our createSound hook
                // and the HTMLMediaElement.play hook fire for the exact same
                // sound, so without a dedupe the same clip gets queued (and
                // played) twice.
                var seen = {};
                var urls = [];
                fresh.forEach(function(s){ if (!seen[s.url]) { seen[s.url] = true; urls.push(s.url); } });
                var audioEl = document.querySelector('audio[src], audio source[src]');
                var aUrl = audioEl ? (audioEl.currentSrc || audioEl.src || '') : '';
                if (aUrl && urls.indexOf(aUrl) === -1) urls.unshift(aUrl);
                return urls;
            }
            function extractText(img){
                try {
                    var node = img.parentElement;
                    for (var depth = 0; depth < 4 && node; depth++){
                        var t = (node.innerText || node.textContent || '').trim();
                        if (t.length > 0) return t.length > 500 ? t.slice(0, 500) : t;
                        node = node.parentElement;
                    }
                } catch (e) { console.log('[brix] text extract err', e); }
                return '';
            }
            function parseTitle(firstLine){
                // DonationAlerts' first line is the whole alert title,
                // "<username> - <amount> <currency>!" — split the name from
                // the amount instead of treating it as one opaque string.
                var idx = firstLine.lastIndexOf(' - ');
                if (idx <= 0) {
                    return {name: (firstLine.length > 0 && firstLine.length < 40) ? firstLine : '', amount: ''};
                }
                var name = firstLine.slice(0, idx).trim();
                var amount = firstLine.slice(idx + 3).trim();
                if (amount.charAt(amount.length - 1) === '!') amount = amount.slice(0, -1).trim();
                return {name: (name.length > 0 && name.length < 40) ? name : '', amount: amount};
            }
            function report(img){
                var src = img.currentSrc || img.src;
                if (!src || src.indexOf('data:') === 0) return;
                // Dedupe by src within a short window only — this widget's
                // test alerts reuse the same image URL for every donation, so
                // a permanent "seen this URL" flag silently ate every repeat
                // donation after the first. The window still absorbs the
                // handful of DOM mutations (attribute changes etc.) one real
                // alert insertion can fire the observer for.
                if (last[src] && (Date.now() - last[src]) < 1500) return;
                last[src] = Date.now();
                var audioUrls = collectAudioUrls();
                var fullText = extractText(img);
                var firstLine = fullText.split('\n')[0].trim();
                var parsed = parseTitle(firstLine);
                var username = parsed.name;
                var amount = parsed.amount;
                // Once the username/amount are isolated, drop the title line
                // from the message body so it isn't shown twice.
                var message = username ? fullText.split('\n').slice(1).join('\n').trim() : fullText;
                console.log('[brix] alert img:', src, 'audio:', JSON.stringify(audioUrls), 'username:', username, 'amount:', amount, 'message:', message);
                try { window.AndroidOverlayBridge.onAlert(src, JSON.stringify(audioUrls), 5000, message, username, amount); } catch(e){ console.log('[brix] onAlert err', e); }
                // A paid TTS/voice readout is generated server-side and can
                // arrive well after the alert image (observed: ~2.8s later on
                // one real donation) — collectAudioUrls() above already ran
                // and reported, so it missed it entirely. Check again shortly
                // after, without delaying the alert's own (fast) appearance.
                // collectAudioUrls() clears window.__brixSounds each time it
                // runs, so this call only ever sees NEW sounds recorded since
                // the check above — no manual dedupe needed between the two.
                setTimeout(function(){
                    var late = collectAudioUrls();
                    if (late.length > 0) {
                        console.log('[brix] late audio:', JSON.stringify(late));
                        try { window.AndroidOverlayBridge.onLateAudio(JSON.stringify(late)); } catch(e){ console.log('[brix] onLateAudio err', e); }
                    }
                }, 3500);
            }
            var obs = new MutationObserver(function(muts){
                for (var i = 0; i < muts.length; i++){
                    var m = muts[i];
                    if (m.type === 'childList'){
                        var nodes = m.addedNodes;
                        for (var j = 0; j < nodes.length; j++){
                            var n = nodes[j];
                            if (n.nodeType !== 1) continue;
                            var imgs = n.tagName === 'IMG' ? [n] : (n.querySelectorAll ? n.querySelectorAll('img') : []);
                            for (var k = 0; k < imgs.length; k++){ var im = imgs[k]; if (im && im.src) report(im); }
                        }
                    } else if (m.type === 'attributes' && m.attributeName === 'src'){
                        var t = m.target;
                        if (t && t.tagName === 'IMG' && t.src) report(t);
                    }
                }
            });
            obs.observe(document, {childList: true, subtree: true, attributes: true, attributeFilter: ['src']});
            console.log('[brix] observer installed');
        })();"""
    }
}
