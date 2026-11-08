package com.staad.island

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.ContactsContract
import android.util.Patterns

/** What a piece of copied or dropped text is, and what the island can offer to do with it. */
data class Card(
    val badge: Int,
    val tint: Int,
    val kind: String,
    val text: String,
    val actions: List<IslandView.Action>,
)

/**
 * OriginOS-style suggestions: copy a link, phone number or email address
 * (or drop text on the island) and get buttons for the apps that handle it,
 * shown with their real icons.
 */
class Suggestions(private val ctx: Context, private val done: () -> Unit) {

    private val pm: PackageManager = ctx.packageManager

    /** A card for anything copied: links, numbers and emails get their own buttons. */
    fun forCopied(raw: String): Card? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return when {
            isLink(t) -> linkCard(t, "Link copied")
            isEmail(t) -> emailCard(t, "Email copied")
            isPhone(t) -> phoneCard(t, "Number copied")
            else -> copiedTextCard(t)
        }
    }

    /** A card for something dropped on the island: always offers at least Share. */
    fun forDropped(clip: ClipData?): Card? {
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val text = item.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            return when {
                isLink(text) -> linkCard(text, "Link dropped")
                isEmail(text) -> emailCard(text, "Email dropped")
                isPhone(text) -> phoneCard(text, "Number dropped")
                else -> textCard(text)
            }
        }
        val uri = item.uri ?: return null
        val type = ctx.contentResolver.getType(uri) ?: clip.description.getMimeType(0) ?: "*/*"
        val send = Intent(Intent.ACTION_SEND).setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Card(
            R.drawable.ic_share, BLUE, if (type.startsWith("image/")) "Photo dropped" else "File dropped",
            uri.lastPathSegment ?: "Shared item",
            listOf(chooser("Share", R.drawable.ic_share, send)),
        )
    }

    // ── Cards ───────────────────────────────────────────────────────────────

    private fun linkCard(t: String, kind: String): Card {
        val url = if (t.startsWith("http", ignoreCase = true)) t else "https://$t"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return Card(
            R.drawable.ic_link, BLUE, kind, pretty(url),
            listOf(
                app("Open", R.drawable.ic_open, view),
                chooser("Share", R.drawable.ic_share, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)),
            ),
        )
    }

    private fun phoneCard(t: String, kind: String): Card {
        val number = t.filter { it.isDigit() || it == '+' }
        return Card(
            R.drawable.ic_phone, GREEN, kind, t,
            listOf(
                app("Call", R.drawable.ic_phone, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))),
                app("Message", R.drawable.ic_message, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))),
                app(
                    "Save", R.drawable.ic_person_add,
                    Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                        .putExtra(ContactsContract.Intents.Insert.PHONE, number),
                ),
            ),
        )
    }

    private fun emailCard(t: String, kind: String): Card = Card(
        R.drawable.ic_mail, ORANGE, kind, t,
        listOf(
            app("Email", R.drawable.ic_mail, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$t"))),
            app(
                "Save", R.drawable.ic_person_add,
                Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                    .putExtra(ContactsContract.Intents.Insert.EMAIL, t),
            ),
        ),
    )

    private fun copiedTextCard(t: String): Card {
        val actions = mutableListOf(
            app("Search", R.drawable.ic_search, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, t.take(500))),
        )
        val translate = Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, t)
        if (resolves(translate)) actions += app("Translate", R.drawable.ic_search, translate)
        actions += chooser("Share", R.drawable.ic_share, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t))
        // looks like a password: never put it on screen
        val shown = if (looksSecret(t)) "••••••••" else t.replace(Regex("\\s+"), " ").take(200)
        return Card(R.drawable.ic_copy, GREY, "Text copied", shown, actions)
    }

    /** One word, 8–64 characters, mixing letters and digits, and not a web address. */
    private fun looksSecret(t: String) = !t.any { it.isWhitespace() } && t.length in 8..64 &&
        t.any { it.isLetter() } && t.any { it.isDigit() } &&
        !Regex("^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$").matches(t)

    private fun resolves(intent: Intent) =
        runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null }.getOrDefault(false)

    private fun textCard(t: String): Card = Card(
        R.drawable.ic_share, BLUE, "Text dropped", t.replace(Regex("\\s+"), " "),
        listOf(
            chooser("Share", R.drawable.ic_share, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)),
            app("Search", R.drawable.ic_search, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, t)),
            IslandView.Action("Copy", null, R.drawable.ic_copy) {
                ctx.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Island", t))
                done()
            },
        ),
    )

    // ── Buttons ─────────────────────────────────────────────────────────────

    /** A button that opens `intent`, shown with the icon of the app that will handle it. */
    private fun app(label: String, glyph: Int, intent: Intent) =
        IslandView.Action(label, handlerIcon(intent), glyph) { launch(intent) }

    private fun chooser(label: String, glyph: Int, intent: Intent) =
        IslandView.Action(label, null, glyph) { launch(Intent.createChooser(intent, null)) }

    private fun handlerIcon(intent: Intent): Drawable? = runCatching {
        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        // "android" is the system's "open with" picker: no single app to show
        if (info.packageName == "android") null else info.loadIcon(pm)
    }.getOrNull()

    private fun launch(intent: Intent) {
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        done()
    }

    // ── Recognising things ──────────────────────────────────────────────────

    private fun isLink(t: String) = !t.contains(' ') &&
        (t.startsWith("http://", true) || t.startsWith("https://", true) || t.startsWith("www.", true)) &&
        Patterns.WEB_URL.matcher(t).matches()

    private fun isEmail(t: String) = !t.contains(' ') && Patterns.EMAIL_ADDRESS.matcher(t).matches()

    private fun isPhone(t: String): Boolean {
        if (!Regex("^[+()\\d][\\d ()\\-.]{5,20}$").matches(t)) return false
        val digits = t.count { it.isDigit() }
        return digits in 7..15
    }

    private fun pretty(url: String) = url
        .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        .removePrefix("www.")
        .replace(Regex("[?#].*$"), "")
        .trimEnd('/')

    companion object {
        const val BLUE = 0xFF0A84FF.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val ORANGE = 0xFFFF9F0A.toInt()
        const val GREY = 0xFF636366.toInt()
    }
}
