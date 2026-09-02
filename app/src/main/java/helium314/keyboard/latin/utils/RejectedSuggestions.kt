package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers suggestions the user pushed away, so the keyboard stops offering them.
 *
 * Rejection is counted rather than absolute. A word rejected once may still be the right one
 * next time -- rejecting "Deus" in one sentence says nothing about the next -- so a single
 * rejection only nudges it down, while repeated rejections bury it. Typing a word on purpose
 * clears its count entirely: deliberate use is the strongest possible signal that the earlier
 * rejections were situational.
 *
 * This is deliberately separate from unlearnFromUserHistory, which only weakens words the user
 * history dictionary itself learned. Words coming from the built-in dictionary cannot be
 * unlearned at all -- it is a read-only binary -- so they are demoted at ranking time instead.
 */
object RejectedSuggestions {

    private const val PREFS_NAME = "rejected_suggestions"
    /** Beyond this, extra rejections change nothing: the word is already at the bottom. */
    private const val MAX_COUNT = 4

    private var prefs: SharedPreferences? = null

    /**
     * In-memory mirror of the stored counts.
     *
     * Ranking asks for the whole set on every keystroke, and reading SharedPreferences there
     * means touching disk in the hot path. The map is small (only rejected words) and this is
     * the single writer, so it can simply be kept in sync on every change.
     */
    @Volatile private var cache: Map<String, Int>? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }

    private fun cached(context: Context): Map<String, Int> {
        cache?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val loaded = prefs(context).all
            .mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }
            .toMap()
        cache = loaded
        return loaded
    }

    /** Records that the user pushed this suggestion away. */
    fun reject(context: Context, word: String) {
        if (word.isBlank()) return
        val key = key(word)
        val store = prefs(context)
        val count = cached(context)[key] ?: 0
        if (count >= MAX_COUNT) return
        cache = cached(context) + (key to count + 1)
        store.edit().putInt(key, count + 1).apply()
    }

    /** Clears a word's rejections, called when the user types it deliberately. */
    fun accept(context: Context, word: String) {
        if (word.isBlank()) return
        val key = key(word)
        if (!cached(context).containsKey(key)) return
        cache = cached(context) - key
        prefs(context).edit().remove(key).apply()
    }

    /** 0 when never rejected, up to MAX_COUNT. */
    fun rejectionCount(context: Context, word: String): Int =
        if (word.isBlank()) 0 else cached(context)[key(word)] ?: 0

    /** Snapshot of every rejected word, for ranking without a lookup per candidate. */
    fun all(context: Context): Map<String, Int> = cached(context)

    fun clear(context: Context) {
        cache = emptyMap()
        prefs(context).edit().clear().apply()
    }

    /** Case-insensitive: rejecting "Deus" should also cover "deus". */
    private fun key(word: String) = word.lowercase()
}
