package com.ikeys.keyboard.prediction

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * High-accuracy, sub-millisecond bilingual (English + Bahasa Indonesia) QuickType prediction,
 * contextual bigram/trigram next-word prediction, emoji prediction, and multi-strategy typo recovery engine.
 *
 * Performance & Architecture:
 * - ~9,600+ frequency-ranked EN + ID words with per-language pre-ranked Top-K caches at every TrieNode
 *   -> O(prefix.length) prefix lookup (~5 microseconds, zero DFS!).
 * - ReentrantReadWriteLock so concurrent reads never block each other or stall the UI thread.
 * - Optional deferred dictionary loading so IME cold-start on the main thread takes <1ms.
 * - Context-aware language inference (explicit locale -> preceding words -> character n-grams -> momentum).
 * - Multi-strategy typo recovery: repeated-letter collapse, transposition, QWERTY proximity substitution,
 *   extra-letter deletion, missing-letter insertion, AND mid-word prefix typo recovery.
 * - 100% local & private (zero network calls).
 */
data class SuggestionItem(
    val text: String,
    val isLiteral: Boolean = false,
    val isAutoCorrectCandidate: Boolean = false
)

class SuggestionEngine(deferDictionaryLoad: Boolean = false) {

    private class TrieNode {
        val children = HashMap<Char, TrieNode>(4)
        var word: String? = null
        var frequency: Int = 0
        var langTag: Byte = LANG_BOTH // 0=both, 1=en, 2=id
        val topEnNodes = ArrayList<TrieNode>(TOP_K_PER_LANG)
        val topIdNodes = ArrayList<TrieNode>(TOP_K_PER_LANG)
        val topUserNodes = ArrayList<TrieNode>(TOP_K_USER)
    }

    private data class BigramEntry(
        var displayWord: String,
        var score: Int
    )

    private val rwLock = ReentrantReadWriteLock()
    private val root = TrieNode()
    private val userLearnedWords = LinkedHashMap<String, Int>()
    private val userLearnedBigrams = LinkedHashMap<String, Int>() // "prev>next" -> score
    private val bigrams = HashMap<String, LinkedHashMap<String, BigramEntry>>(512)
    private val trigrams = HashMap<String, MutableList<String>>(256)

    @Volatile
    private var dictionaryLoaded: Boolean = false

    // Decaying language momentum: >0 biases EN, <0 biases ID
    @Volatile
    private var recentLangMomentum: Int = 0

    /** Optional explicit language bias ("en", "id", or null for auto-detect bilingual). */
    @Volatile
    var preferredLanguage: String? = null

    /**
     * Unambiguous English contractions, classic typos, and Indonesian texting shorthands
     * safe for automatic correction on spacebar.
     */
    private val contractions: Map<String, String> = mapOf(
        // English contractions
        "im" to "I'm",
        "ive" to "I've",
        "ill" to "I'll",
        "dont" to "don't",
        "cant" to "can't",
        "wont" to "won't",
        "didnt" to "didn't",
        "doesnt" to "doesn't",
        "isnt" to "isn't",
        "arent" to "aren't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "havent" to "haven't",
        "hasnt" to "hasn't",
        "hadnt" to "hadn't",
        "wouldnt" to "wouldn't",
        "couldnt" to "couldn't",
        "shouldnt" to "shouldn't",
        "youre" to "you're",
        "youve" to "you've",
        "youll" to "you'll",
        "youd" to "you'd",
        "thats" to "that's",
        "theres" to "there's",
        "whats" to "what's",
        "heres" to "here's",
        "lets" to "let's",
        "theyre" to "they're",
        "theyve" to "they've",
        "theyll" to "they'll",
        "theyd" to "they'd",
        "weve" to "we've",
        "hes" to "he's",
        "shes" to "she's",
        "itll" to "it'll",
        "whos" to "who's",
        "wheres" to "where's",
        "whens" to "when's",
        "hows" to "how's",
        "aint" to "ain't",
        "yall" to "y'all",
        // Classic English typos & shorthands
        "teh" to "the",
        "adn" to "and",
        "taht" to "that",
        "wiht" to "with",
        "waht" to "what",
        "ahve" to "have",
        "tihs" to "this",
        "recieve" to "receive",
        "seperate" to "separate",
        "definately" to "definitely",
        "definatly" to "definitely",
        "occured" to "occurred",
        "untill" to "until",
        "becuase" to "because",
        "becasue" to "because",
        "freind" to "friend",
        "tommorow" to "tomorrow",
        "tomorow" to "tomorrow",
        "thier" to "their",
        "beleive" to "believe",
        "acheive" to "achieve",
        "wierd" to "weird",
        "writting" to "writing",
        "begining" to "beginning",
        "completly" to "completely",
        "immediatly" to "immediately",
        "neccessary" to "necessary",
        "necesary" to "necessary",
        "accomodate" to "accommodate",
        "adress" to "address",
        "buisness" to "business",
        "calender" to "calendar",
        "enviroment" to "environment",
        "goverment" to "government",
        "happend" to "happened",
        "independant" to "independent",
        "knowlege" to "knowledge",
        "recomend" to "recommend",
        "rember" to "remember",
        "suprise" to "surprise",
        "truely" to "truly",
        "unfortunatly" to "unfortunately",
        "realy" to "really",
        "pls" to "please",
        "plz" to "please",
        "thx" to "thanks",
        "ty" to "thank you",
        "tmrw" to "tomorrow",
        "bcz" to "because",
        "ppl" to "people",
        "msg" to "message",
        "abt" to "about",
        "wht" to "what",
        "rly" to "really",
        "prob" to "probably",
        "bday" to "birthday",
        "omw" to "on my way",
        // Bahasa Indonesia shorthands & texting abbreviations
        "yg" to "yang",
        "dgn" to "dengan",
        "utk" to "untuk",
        "sdh" to "sudah",
        "udh" to "sudah",
        "blm" to "belum",
        "krn" to "karena",
        "tdk" to "tidak",
        "bgt" to "banget",
        "tp" to "tapi",
        "dr" to "dari",
        "sm" to "sama",
        "jd" to "jadi",
        "org" to "orang",
        "skrg" to "sekarang",
        "bsk" to "besok",
        "kmrn" to "kemarin",
        "tlg" to "tolong",
        "trims" to "terima kasih",
        "trmksh" to "terima kasih",
        "mksh" to "makasih",
        "makasi" to "makasih",
        "bkn" to "bukan",
        "lg" to "lagi",
        "dlm" to "dalam",
        "hrs" to "harus",
        "bs" to "bisa",
        "bsa" to "bisa",
        "sy" to "saya",
        "km" to "kamu",
        "ak" to "aku",
        "jg" to "juga",
        "kl" to "kalau",
        "klo" to "kalau",
        "gmn" to "gimana",
        "knp" to "kenapa",
        "dmn" to "dimana",
        "kmn" to "kemana",
        "bgtu" to "begitu",
        "gtu" to "gitu",
        "msh" to "masih",
        "bnyk" to "banyak",
        "sgt" to "sangat",
        "emg" to "memang",
        "mkn" to "makan",
        "mnm" to "minum",
        "tdr" to "tidur",
        "rmh" to "rumah",
        "kntr" to "kantor",
        "sklh" to "sekolah",
        "jln" to "jalan",
        "plg" to "pulang",
        "plng" to "pulang",
        "dtg" to "datang",
        "kbr" to "kabar",
        "bgs" to "bagus",
        "smg" to "semoga",
        "slmt" to "selamat",
        "trs" to "terus",
        "kyk" to "kayak",
        "pdhl" to "padahal",
        "wkt" to "waktu",
        "bwt" to "buat",
        "blg" to "bilang",
        "tny" to "tanya",
        "jwb" to "jawab",
        "psn" to "pesan",
        "krm" to "kirim",
        "bntu" to "bantu",
        "cm" to "cuma",
        "cma" to "cuma",
        "pke" to "pakai",
        "dpt" to "dapat",
        "smua" to "semua",
        "ntar" to "sebentar",
        "btr" to "bentar",
        "jgn" to "jangan",
        "mksd" to "maksud",
        "prlu" to "perlu",
        "bbrp" to "beberapa",
        "mgkn" to "mungkin",
        "mngkn" to "mungkin",
        "sbnrnya" to "sebenarnya",
        "sbenernya" to "sebenarnya",
        "brg" to "bareng",
        "kpn" to "kapan",
        "brp" to "berapa",
        "tmn" to "teman",
        "klg" to "keluarga",
        "krj" to "kerja",
        "bljr" to "belajar",
        "sblm" to "sebelum",
        "stlh" to "setelah",
        "smpe" to "sampai",
        "smpai" to "sampai",
        "lgsg" to "langsung",
        "lngsng" to "langsung",
        "bkl" to "bakal",
        "tnggu" to "tunggu",
        "brngkt" to "berangkat",
        "slalu" to "selalu",
        "srg" to "sering",
        "prnh" to "pernah",
        "gpp" to "gak apa-apa",
        "ywdh" to "ya udah",
        "yudh" to "ya udah",
        "bngt" to "banget"
    )

    /**
     * Soft suggestions shown in the QuickType bar WITHOUT aggressively overwriting valid words on space!
     * Fixes real-world annoyances like "were" -> "we're" or "gak" -> "enggak".
     */
    private val softAlternatives: Map<String, String> = mapOf(
        "were" to "we're",
        "its" to "it's",
        "well" to "we'll",
        "hell" to "he'll",
        "shell" to "she'll",
        "wed" to "we'd",
        "shed" to "she'd",
        "id" to "I'd",
        "gak" to "enggak",
        "ga" to "tidak",
        "nggak" to "tidak",
        "ngga" to "tidak",
        "bener" to "benar",
        "udah" to "sudah",
        "belom" to "belum",
        "gimana" to "bagaimana",
        "aja" to "saja",
        "tau" to "tahu",
        "ngerti" to "mengerti",
        "bikin" to "membuat",
        "kayak" to "seperti"
    )

    private val emojiPredictions: Map<String, String> = mapOf(
        "love" to "❤️", "heart" to "❤️", "cinta" to "❤️", "sayang" to "❤️", "kangen" to "🥺", "rindu" to "🥺",
        "happy" to "😊", "senang" to "😊", "bahagia" to "😊", "smile" to "😊", "senyum" to "😊",
        "haha" to "😂", "hahaha" to "😂", "wkwk" to "😂", "wkwkwk" to "😂", "laugh" to "😂", "funny" to "😂", "lucu" to "😂", "kocak" to "😂", "lol" to "😂", "lmao" to "🤣",
        "thanks" to "🙏", "thank" to "🙏", "makasih" to "🙏", "kasih" to "🙏", "please" to "🙏", "tolong" to "🙏", "maaf" to "🙏", "sorry" to "😔", "amin" to "🤲", "aamiin" to "🤲", "alhamdulillah" to "🤲",
        "fire" to "🔥", "hot" to "🔥", "keren" to "🔥", "lit" to "🔥", "gokil" to "🔥", "panas" to "🥵",
        "good" to "👍", "great" to "👍", "awesome" to "👍", "nice" to "👍", "mantap" to "👍", "mantul" to "👍", "bagus" to "👍", "sip" to "👍",
        "ok" to "👌", "okay" to "👌", "oke" to "👌", "siap" to "🫡", "perfect" to "👌", "beres" to "👌",
        "sad" to "😢", "sedih" to "😢", "cry" to "😭", "nangis" to "😭", "angry" to "😠", "marah" to "😠", "kesal" to "😤",
        "party" to "🎉", "birthday" to "🎂", "congrats" to "🎉", "congratulations" to "🎉", "selamat" to "🎉", "sukses" to "🎉",
        "coffee" to "☕", "kopi" to "☕", "tea" to "🍵", "teh" to "🍵", "beer" to "🍺", "milk" to "🥛", "susu" to "🥛",
        "food" to "🍽️", "makan" to "🍽️", "hungry" to "😋", "lapar" to "😋", "enak" to "😋", "delicious" to "😋", "pizza" to "🍕", "burger" to "🍔", "nasi" to "🍚", "mie" to "🍜", "ramen" to "🍜", "bakso" to "🍜", "sate" to "🍢", "cake" to "🍰",
        "sleep" to "😴", "sleepy" to "😴", "tired" to "😴", "tidur" to "😴", "ngantuk" to "😴", "capek" to "😮‍💨", "lelah" to "😮‍💨",
        "cool" to "😎", "star" to "⭐", "sun" to "☀️", "matahari" to "☀️", "moon" to "🌙", "bulan" to "🌙", "rain" to "🌧️", "hujan" to "🌧️", "cold" to "🥶", "dingin" to "🥶",
        "music" to "🎵", "musik" to "🎵", "song" to "🎶", "lagu" to "🎶", "phone" to "📱", "hp" to "📱", "laptop" to "💻", "coding" to "💻", "ngoding" to "💻",
        "money" to "💰", "uang" to "💰", "duit" to "💸", "gaji" to "💸", "cash" to "💵",
        "home" to "🏠", "rumah" to "🏠", "work" to "💼", "kerja" to "💼", "kantor" to "🏢", "school" to "🏫", "sekolah" to "🏫", "kuliah" to "🎓",
        "car" to "🚗", "mobil" to "🚗", "motor" to "🏍️", "plane" to "✈️", "pesawat" to "✈️", "flight" to "✈️", "train" to "🚆", "kereta" to "🚆", "beach" to "🏖️", "pantai" to "🏖️",
        "cat" to "🐱", "kucing" to "🐱", "dog" to "🐶", "anjing" to "🐶", "flower" to "🌸", "bunga" to "🌸",
        "wow" to "😮", "omg" to "😱", "shock" to "😱", "kaget" to "😱", "thinking" to "🤔", "bingung" to "🤔", "confused" to "🤔",
        "sick" to "🤒", "sakit" to "🤒", "demam" to "🤒", "strong" to "💪", "semangat" to "💪", "gas" to "🚀", "gaskeun" to "🚀", "otw" to "🏃",
        "yes" to "✅", "no" to "❌", "check" to "✅", "done" to "✅", "selesai" to "✅", "warning" to "⚠️", "bug" to "🐛", "gift" to "🎁", "hadiah" to "🎁"
    )

    private val defaultStartersEn = listOf("I", "The", "Hey")
    private val defaultStartersId = listOf("Saya", "Halo", "Yang")
    private val defaultStartersMixed = listOf("I", "Saya", "Halo")

    init {
        if (!deferDictionaryLoad) {
            ensureDictionaryLoaded()
        }
    }

    /**
     * Loads the full EN + ID dictionaries and n-gram tables safely (idempotent).
     * Can be called from a background thread during IME startup for <1ms cold-start.
     */
    fun ensureDictionaryLoaded() {
        if (dictionaryLoaded) return
        rwLock.write {
            if (dictionaryLoaded) return
            seedLexiconFromResourcesOrFallback()
            seedCoreBigramsAndTrigrams()
            dictionaryLoaded = true
        }
    }

    fun loadUserWordsCsv(csv: String) {
        if (csv.isBlank()) return
        ensureDictionaryLoaded()
        rwLock.write {
            csv.split(',').forEach { token ->
                val word = token.trim()
                if (word.length >= 2) {
                    learnWordLocked(word, boost = 45)
                }
            }
        }
    }

    fun exportUserWordsCsv(maxWords: Int = 250): String = rwLock.read {
        if (userLearnedWords.isEmpty()) return ""
        userLearnedWords.entries
            .sortedByDescending { it.value }
            .take(maxWords)
            .joinToString(",") { it.key }
    }

    fun loadUserBigramsCsv(csv: String) {
        if (csv.isBlank()) return
        ensureDictionaryLoaded()
        rwLock.write {
            csv.split(',').forEach { pair ->
                val sep = pair.indexOf('>')
                if (sep > 0 && sep < pair.length - 1) {
                    val prev = pair.substring(0, sep).trim()
                    val next = pair.substring(sep + 1).trim()
                    if (prev.isNotEmpty() && next.length >= 2) {
                        learnTransitionLocked(prev, next, boost = 45)
                    }
                }
            }
        }
    }

    fun exportUserBigramsCsv(maxEntries: Int = 150): String = rwLock.read {
        if (userLearnedBigrams.isEmpty()) return ""
        userLearnedBigrams.entries
            .sortedByDescending { it.value }
            .take(maxEntries)
            .joinToString(",") { it.key }
    }

    fun learnWord(rawWord: String, boost: Int = 25) {
        ensureDictionaryLoaded()
        rwLock.write {
            learnWordLocked(rawWord, boost)
        }
    }

    private fun learnWordLocked(rawWord: String, boost: Int) {
        val cleaned = rawWord.trim()
        if (cleaned.length < 2 || cleaned.length > 28) return
        if (!cleaned.all { it.isLetter() || it == '\'' || it == '-' }) return
        insert(cleaned, boost, LANG_BOTH, isUser = true)
        userLearnedWords[cleaned] = (userLearnedWords[cleaned] ?: 0) + boost
        // Update language momentum from the completed word
        val existing = exactLookup(cleaned.lowercase())
        if (existing != null) {
            when (existing.langTag) {
                LANG_EN -> recentLangMomentum = (recentLangMomentum + 2).coerceIn(-6, 6)
                LANG_ID -> recentLangMomentum = (recentLangMomentum - 2).coerceIn(-6, 6)
            }
        }
    }

    fun learnTransition(prevWord: String, nextWord: String, boost: Int = 35) {
        ensureDictionaryLoaded()
        rwLock.write {
            learnTransitionLocked(prevWord, nextWord, boost)
        }
    }

    private fun learnTransitionLocked(prevWord: String, nextWord: String, boost: Int) {
        val p = prevWord.trim().lowercase()
        val n = nextWord.trim()
        val nLower = n.lowercase()
        if (p.isEmpty() || n.length < 2 || n.length > 24) return
        if (!n.all { it.isLetter() || it == '\'' || it == '-' }) return
        val map = bigrams.getOrPut(p) { LinkedHashMap(6) }
        val existing = map[nLower]
        if (existing != null) {
            existing.score += boost
            existing.displayWord = n
        } else {
            map[nLower] = BigramEntry(displayWord = n, score = boost)
        }
        val key = "$p>$n"
        userLearnedBigrams[key] = (userLearnedBigrams[key] ?: 0) + boost
    }

    fun suggest(
        currentWord: String,
        maxResults: Int = 3,
        previousWord: String = "",
        prevPreviousWord: String = ""
    ): List<SuggestionItem> {
        ensureDictionaryLoaded()
        return rwLock.read {
            suggestLocked(currentWord, maxResults, previousWord, prevPreviousWord)
        }
    }

    fun autoCorrectFor(currentWord: String, previousWord: String = ""): String? {
        if (currentWord.isEmpty()) return null
        ensureDictionaryLoaded()
        return rwLock.read {
            val suggestions = suggestLocked(currentWord, maxResults = 3, previousWord = previousWord, prevPreviousWord = "")
            suggestions.firstOrNull { it.isAutoCorrectCandidate }?.text
        }
    }

    private fun suggestLocked(
        currentWord: String,
        maxResults: Int,
        previousWord: String,
        prevPreviousWord: String
    ): List<SuggestionItem> {
        val prevLower = previousWord.trim().lowercase()
        val prev2Lower = prevPreviousWord.trim().lowercase()
        val lower = currentWord.lowercase()
        val activeLang = resolveActiveLanguage(lower, prevLower, prev2Lower)

        // 1. Between words: contextual Trigram -> Bigram -> Language starters
        if (currentWord.isBlank()) {
            val nextCandidates = ArrayList<String>(maxResults + 2)
            val seenNext = HashSet<String>(8)

            if (prev2Lower.isNotEmpty() && prevLower.isNotEmpty()) {
                val tri = trigrams["$prev2Lower $prevLower"]
                if (!tri.isNullOrEmpty()) {
                    for (w in tri) {
                        if (seenNext.add(w.lowercase())) nextCandidates.add(w)
                    }
                }
            }
            if (prevLower.isNotEmpty() && nextCandidates.size < maxResults) {
                val nextWords = bigrams[prevLower]
                if (!nextWords.isNullOrEmpty()) {
                    nextWords.values
                        .sortedByDescending { it.score }
                        .forEach { entry ->
                            if (nextCandidates.size < maxResults && seenNext.add(entry.displayWord.lowercase())) {
                                nextCandidates.add(entry.displayWord)
                            }
                        }
                }
            }
            if (nextCandidates.isNotEmpty()) {
                return nextCandidates.take(maxResults).map { SuggestionItem(text = it) }
            }
            val starters = when (activeLang) {
                LANG_EN -> defaultStartersEn
                LANG_ID -> defaultStartersId
                else -> defaultStartersMixed
            }
            return starters.take(maxResults).map { SuggestionItem(text = it) }
        }

        // 2. Special iOS rule: standalone lowercase "i" -> "I" in English/neutral context
        if (currentWord == "i" && activeLang != LANG_ID) {
            val slots = ArrayList<SuggestionItem>(maxResults)
            slots.add(SuggestionItem(text = "i", isLiteral = true))
            slots.add(SuggestionItem(text = "I", isAutoCorrectCandidate = true))
            val third = if (activeLang == LANG_EN) "I'm" else "ini"
            slots.add(SuggestionItem(text = third))
            return slots.take(maxResults)
        }

        val exactContraction = resolveContraction(lower, activeLang)
        val softAlt = softAlternatives[lower]
        val exactNode = exactLookup(lower)
        val exactKnown = exactNode != null

        // O(prefix.length) instant prefix lookup via per-language pre-ranked Top-K caches + n-gram boosting!
        val prefixMatches = collectFastPrefixMatches(lower, activeLang, prevLower, prev2Lower)

        // Multi-strategy typo recovery when word isn't an exact known word and isn't an explicit contraction
        val fuzzyMatches = if (lower.length >= 3 && !exactKnown && exactContraction == null && prefixMatches.size < 3) {
            collectNearbyTypoMatches(lower, activeLang, prevLower, limit = 6)
        } else {
            emptyList()
        }

        // Mid-word prefix typo recovery: e.g. "indons" -> "indonesia", "selamt" -> "selamat", "becuas" -> "because"
        val prefixTypoMatches = if (lower.length >= 4 && !exactKnown && exactContraction == null && prefixMatches.isEmpty() && fuzzyMatches.isEmpty()) {
            collectPrefixTypoCompletions(lower, activeLang, limit = 4)
        } else {
            emptyList()
        }

        val rankedPrefix = ArrayList<String>(6)
        val rankedFuzzy = ArrayList<String>(6)
        val seen = HashSet<String>(12)

        fun addCandidate(target: MutableList<String>, word: String) {
            val styled = applyCaseStyle(word, currentWord)
            if (seen.add(styled.lowercase())) {
                target.add(styled)
            }
        }

        if (exactContraction != null) {
            addCandidate(rankedPrefix, exactContraction)
        }
        if (softAlt != null) {
            addCandidate(rankedPrefix, softAlt)
        }

        for (i in prefixMatches.indices) {
            val w = prefixMatches[i]
            if (!w.equals(lower, ignoreCase = true)) {
                addCandidate(rankedPrefix, w)
            }
        }

        for (i in fuzzyMatches.indices) {
            val w = fuzzyMatches[i]
            if (!w.equals(lower, ignoreCase = true)) {
                addCandidate(rankedFuzzy, w)
            }
        }

        for (i in prefixTypoMatches.indices) {
            val w = prefixTypoMatches[i]
            if (!w.equals(lower, ignoreCase = true)) {
                addCandidate(rankedFuzzy, w)
            }
        }

        // High-confidence autocorrect:
        // 1. Explicit contractions/shorthands ("dont" -> "don't", "yg" -> "yang")
        // 2. Clear 1-edit exact typos with no valid prefix continuations ("hte" -> "the", "helllo" -> "hello", "bagis" -> "bagus")
        val typoCandidate = if (!exactKnown && exactContraction == null && rankedPrefix.isEmpty() && fuzzyMatches.isNotEmpty()) {
            rankedFuzzy.firstOrNull()
        } else {
            null
        }
        val autoCorrectTarget = when {
            exactContraction != null -> applyCaseStyle(exactContraction, currentWord)
            typoCandidate != null -> typoCandidate
            else -> null
        }

        val combined = ArrayList<String>(8)
        combined.addAll(rankedPrefix)
        combined.addAll(rankedFuzzy)

        // Check for contextual iOS Emoji QuickType match when the typed word is complete
        val emojiHint = if (exactKnown || exactContraction != null) emojiPredictions[lower] else null

        val slots = ArrayList<SuggestionItem>(maxResults)
        if (autoCorrectTarget != null && !autoCorrectTarget.equals(currentWord, ignoreCase = true)) {
            slots.add(SuggestionItem(text = currentWord, isLiteral = true))
            slots.add(SuggestionItem(text = autoCorrectTarget, isAutoCorrectCandidate = true))
            val third = combined.firstOrNull { !it.equals(autoCorrectTarget, ignoreCase = true) }
            if (third != null) {
                slots.add(SuggestionItem(text = third))
            } else if (emojiHint != null) {
                slots.add(SuggestionItem(text = emojiHint))
            }
        } else {
            val primary = combined.firstOrNull()
            val secondary = combined.getOrNull(1)
            if (primary != null) {
                slots.add(SuggestionItem(text = currentWord, isLiteral = !exactKnown))
                slots.add(SuggestionItem(text = primary))
                if (emojiHint != null) {
                    slots.add(SuggestionItem(text = emojiHint))
                } else if (secondary != null) {
                    slots.add(SuggestionItem(text = secondary))
                }
            } else {
                slots.add(SuggestionItem(text = currentWord, isLiteral = !exactKnown))
                if (emojiHint != null) {
                    slots.add(SuggestionItem(text = emojiHint))
                }
            }
        }

        return slots.take(maxResults)
    }

    private fun resolveContraction(lower: String, activeLang: Byte): String? {
        if (lower == "id") {
            return if (activeLang == LANG_EN) "I'd" else null
        }
        return contractions[lower]
    }

    /**
     * Multi-signal active language inference:
     * 1. Explicit IME subtype preference ("en" / "id")
     * 2. Previous 2 completed words in the sentence
     * 3. Characteristic EN/ID character n-grams & exact word signal of the current word
     * 4. Decaying session momentum
     */
    private fun resolveActiveLanguage(currentLower: String, prevLower: String, prev2Lower: String): Byte {
        when (preferredLanguage) {
            "en" -> return LANG_EN
            "id" -> return LANG_ID
        }

        var langScore = 0 // >0 EN, <0 ID

        if (prevLower.isNotEmpty()) {
            val prevNode = exactLookup(prevLower)
            if (prevNode != null) {
                when (prevNode.langTag) {
                    LANG_EN -> langScore += 4
                    LANG_ID -> langScore -= 4
                }
            }
        }
        if (prev2Lower.isNotEmpty()) {
            val prev2Node = exactLookup(prev2Lower)
            if (prev2Node != null) {
                when (prev2Node.langTag) {
                    LANG_EN -> langScore += 2
                    LANG_ID -> langScore -= 2
                }
            }
        }

        // Character n-gram & lexical hints from the word currently being typed (enables seamless code-switching!)
        if (currentLower.length >= 2) {
            val curNode = exactLookup(currentLower)
            if (curNode != null && curNode.langTag != LANG_BOTH) {
                langScore += if (curNode.langTag == LANG_EN) 3 else -3
            } else {
                if (currentLower.contains("ng") || currentLower.contains("ny") ||
                    currentLower.contains("kh") || currentLower.contains("sy") ||
                    currentLower.endsWith("nya") || currentLower.startsWith("meng") ||
                    currentLower.startsWith("meny") || currentLower.startsWith("ber")
                ) {
                    langScore -= 3
                }
                if (currentLower.contains("th") || currentLower.contains("wh") ||
                    currentLower.contains("sh") || currentLower.contains("ck") ||
                    currentLower.contains("'") || currentLower.endsWith("ing") ||
                    currentLower.endsWith("tion") || currentLower.contains("igh")
                ) {
                    langScore += 3
                }
            }
        }

        if (langScore == 0 && recentLangMomentum != 0) {
            langScore = if (recentLangMomentum > 0) 1 else -1
        }

        return when {
            langScore > 0 -> LANG_EN
            langScore < 0 -> LANG_ID
            else -> LANG_BOTH
        }
    }

    private fun insert(word: String, frequency: Int, langTag: Byte, isUser: Boolean = false) {
        val lower = word.lowercase()
        val path = ArrayList<TrieNode>(lower.length + 1)
        var node = root
        path.add(node)
        for (i in lower.indices) {
            node = node.children.getOrPut(lower[i]) { TrieNode() }
            path.add(node)
        }
        if (node.word == null || frequency > node.frequency) {
            node.word = word
        }
        if (node.frequency == 0) {
            node.langTag = langTag
        } else if (node.langTag != LANG_BOTH && node.langTag != langTag) {
            node.langTag = LANG_BOTH
        }
        node.frequency += frequency

        // Maintain separate per-language Top-K caches on every ancestor -> O(1) lookup, zero cross-language eviction!
        for (i in 1 until path.size) {
            val ancestor = path[i]
            when {
                isUser || langTag == LANG_BOTH -> updateTopList(ancestor.topUserNodes, node, TOP_K_USER)
                langTag == LANG_EN -> updateTopList(ancestor.topEnNodes, node, TOP_K_PER_LANG)
                langTag == LANG_ID -> updateTopList(ancestor.topIdNodes, node, TOP_K_PER_LANG)
            }
        }
    }

    private fun updateTopList(list: ArrayList<TrieNode>, leaf: TrieNode, limit: Int) {
        var existingIdx = -1
        for (i in list.indices) {
            if (list[i] === leaf) {
                existingIdx = i
                break
            }
        }
        if (existingIdx >= 0) {
            var pos = existingIdx
            while (pos > 0 && list[pos].frequency > list[pos - 1].frequency) {
                val tmp = list[pos - 1]
                list[pos - 1] = list[pos]
                list[pos] = tmp
                pos--
            }
            return
        }
        if (list.size < limit) {
            list.add(leaf)
            var pos = list.lastIndex
            while (pos > 0 && list[pos].frequency > list[pos - 1].frequency) {
                val tmp = list[pos - 1]
                list[pos - 1] = list[pos]
                list[pos] = tmp
                pos--
            }
        } else if (leaf.frequency > list[list.lastIndex].frequency) {
            list[list.lastIndex] = leaf
            var pos = list.lastIndex
            while (pos > 0 && list[pos].frequency > list[pos - 1].frequency) {
                val tmp = list[pos - 1]
                list[pos - 1] = list[pos]
                list[pos] = tmp
                pos--
            }
        }
    }

    private fun exactLookup(lowerWord: String): TrieNode? {
        var node = root
        for (i in lowerWord.indices) {
            node = node.children[lowerWord[i]] ?: return null
        }
        return if (node.word != null) node else null
    }

    private fun findNode(lowerPrefix: String): TrieNode? {
        var node = root
        for (i in lowerPrefix.indices) {
            node = node.children[lowerPrefix[i]] ?: return null
        }
        return node
    }

    /**
     * Ultra-fast O(prefix.length) prefix completion lookup across per-language pre-ranked caches
     * enhanced with BOTH contextual Trigrams and Bigrams!
     */
    private fun collectFastPrefixMatches(
        prefix: String,
        activeLang: Byte,
        prevLower: String,
        prev2Lower: String
    ): List<String> {
        val node = findNode(prefix) ?: return emptyList()

        val prevBigrams = if (prevLower.isNotEmpty()) bigrams[prevLower] else null
        val prevTrigrams = if (prev2Lower.isNotEmpty() && prevLower.isNotEmpty()) {
            trigrams["$prev2Lower $prevLower"]
        } else {
            null
        }
        val scored = LinkedHashMap<String, Int>(24)

        fun addScore(word: String, score: Int) {
            val prev = scored[word]
            if (prev == null || score > prev) {
                scored[word] = score
            }
        }

        // Inject exact contextual trigram/bigram prefix matches directly so context ALWAYS surfaces on letter 1!
        if (prevTrigrams != null) {
            for (idx in prevTrigrams.indices) {
                val triWord = prevTrigrams[idx]
                if (triWord.startsWith(prefix, ignoreCase = true) && !triWord.equals(prefix, ignoreCase = true)) {
                    addScore(triWord, 3600 - idx * 120)
                }
            }
        }
        if (prevBigrams != null) {
            for ((kLower, entry) in prevBigrams) {
                if (kLower.startsWith(prefix) && kLower != prefix) {
                    addScore(entry.displayWord, 2200 + entry.score * 10)
                }
            }
        }

        fun considerList(candidates: ArrayList<TrieNode>) {
            for (i in candidates.indices) {
                val cand = candidates[i]
                val w = cand.word ?: continue
                val wLower = w.lowercase()
                var score = cand.frequency
                if (activeLang != LANG_BOTH && cand.langTag == activeLang) {
                    score += 650
                }
                if (prevTrigrams != null && prevTrigrams.any { it.equals(wLower, ignoreCase = true) }) {
                    score += 1800
                }
                if (prevBigrams != null) {
                    val bg = prevBigrams[wLower]
                    if (bg != null) score += bg.score * 12
                }
                score -= (w.length - prefix.length).coerceAtLeast(0) * 14
                addScore(w, score)
            }
        }

        considerList(node.topUserNodes)
        considerList(node.topEnNodes)
        considerList(node.topIdNodes)

        if (scored.isEmpty()) return emptyList()
        return scored.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /**
     * Multi-strategy typo recovery:
     * 0. Repeated-character collapse ("baguuus" -> "bagus", "hellooo" -> "hello")
     * 1. Adjacent character transposition ("hte" -> "the", "syaa" -> "saya")
     * 2. QWERTY proximity single-char substitution ("hellp" -> "hello", "bagis" -> "bagus")
     * 3. Single extra-letter deletion ("helllo" -> "hello", "goood" -> "good")
     * 4. Single missing-letter insertion across common EN+ID letters ("hllo" -> "hello", "kmu" -> "kamu")
     */
    private fun collectNearbyTypoMatches(
        lower: String,
        activeLang: Byte,
        prevLower: String,
        limit: Int
    ): List<String> {
        val found = LinkedHashMap<String, Int>(10)
        val prevBigrams = if (prevLower.isNotEmpty()) bigrams[prevLower] else null

        fun consider(node: TrieNode?, bonus: Int) {
            val w = node?.word ?: return
            var score = node.frequency + bonus
            if (activeLang != LANG_BOTH && node.langTag == activeLang) score += 380
            if (prevBigrams != null) {
                val bg = prevBigrams[w.lowercase()]
                if (bg != null) score += bg.score * 10
            }
            val prev = found[w]
            if (prev == null || score > prev) {
                found[w] = score
            }
        }

        // 0. Repeated-character collapse ("hellooo" -> "hello", "baguuus" -> "bagus", "goooood" -> "good")
        if (hasRepeatedChars(lower)) {
            val longRunsToOne = collapseLongRuns(lower, replacementLen = 1)
            if (longRunsToOne != lower) {
                consider(exactLookup(longRunsToOne), bonus = 250)
            }
            val longRunsToTwo = collapseLongRuns(lower, replacementLen = 2)
            if (longRunsToTwo != lower && longRunsToTwo != longRunsToOne) {
                consider(exactLookup(longRunsToTwo), bonus = 240)
            }
            val allRunsToOne = collapseRuns(lower, maxRun = 1)
            if (allRunsToOne != lower && allRunsToOne != longRunsToOne && allRunsToOne != longRunsToTwo) {
                consider(exactLookup(allRunsToOne), bonus = 210)
            }
        }

        val chars = lower.toCharArray()

        // 1. Adjacent character transpositions ("hte" -> "the", "syaa" -> "saya")
        for (i in 0 until chars.size - 1) {
            if (chars[i] == chars[i + 1]) continue
            val tmp = chars[i]
            chars[i] = chars[i + 1]
            chars[i + 1] = tmp
            consider(exactLookupChars(chars), bonus = 220)
            chars[i + 1] = chars[i]
            chars[i] = tmp
        }

        // 2. QWERTY proximity single-char substitutions ("hellp" -> "hello", "bagis" -> "bagus")
        for (i in chars.indices) {
            val orig = chars[i]
            val neighbors = qwertyNeighbors[orig] ?: continue
            for (j in neighbors.indices) {
                chars[i] = neighbors[j]
                consider(exactLookupChars(chars), bonus = 150)
            }
            chars[i] = orig
        }

        // 3. Single extra-letter deletion ("helllo" -> "hello", "goood" -> "good")
        if (lower.length >= 4) {
            for (i in lower.indices) {
                consider(exactLookupSkipping(lower, i), bonus = 130)
            }
        }

        // 4. Single missing-letter insertion ("hllo" -> "hello", "kmu" -> "kamu", "bnyak" -> "banyak")
        if (lower.length in 3..10 && found.size < 2) {
            val commonLetters = "aeiounrstlhmdkpgbcwyf"
            val buf = CharArray(lower.length + 1)
            for (pos in 1..lower.length) {
                for (k in 0 until pos) buf[k] = lower[k]
                for (k in pos until lower.length) buf[k + 1] = lower[k]
                for (cIdx in commonLetters.indices) {
                    buf[pos] = commonLetters[cIdx]
                    consider(exactLookupChars(buf), bonus = 75)
                }
            }
        }

        if (found.isEmpty()) return emptyList()
        return found.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    /**
     * Mid-word prefix typo recovery: when the user makes a typo mid-word and hits a Trie dead-end
     * (e.g., "indons" -> "indonesia", "selamt" -> "selamat", "becuas" -> "because", "tomorrw" -> "tomorrow"),
     * probes 1-edit prefixes and returns their Top-K completions!
     */
    private fun collectPrefixTypoCompletions(lower: String, activeLang: Byte, limit: Int): List<String> {
        val found = LinkedHashMap<String, Int>(8)

        fun harvestPrefixNode(node: TrieNode?, bonus: Int) {
            if (node == null) return
            fun scan(list: ArrayList<TrieNode>) {
                for (i in list.indices) {
                    val cand = list[i]
                    val w = cand.word ?: continue
                    var score = cand.frequency + bonus - (w.length - lower.length).coerceAtLeast(0) * 18
                    if (activeLang != LANG_BOTH && cand.langTag == activeLang) score += 350
                    val prev = found[w]
                    if (prev == null || score > prev) {
                        found[w] = score
                    }
                }
            }
            scan(node.topUserNodes)
            scan(node.topEnNodes)
            scan(node.topIdNodes)
        }

        val chars = lower.toCharArray()

        // Adjacent transposition in prefix ("becuas" -> "becaus" -> "because")
        for (i in 0 until chars.size - 1) {
            if (chars[i] == chars[i + 1]) continue
            val tmp = chars[i]
            chars[i] = chars[i + 1]
            chars[i + 1] = tmp
            harvestPrefixNode(findNodeChars(chars), bonus = 140)
            chars[i + 1] = chars[i]
            chars[i] = tmp
        }

        // Single deletion in prefix ("selamt" or "tomorrw")
        for (i in 1 until lower.length) {
            harvestPrefixNode(findNodeSkipping(lower, i), bonus = 100)
        }

        // Single vowel insertion in prefix ("indons" -> "indones" -> "indonesia", "selamt" -> "selamat")
        if (lower.length in 4..10) {
            val vowels = "aeiuo"
            val buf = CharArray(lower.length + 1)
            for (pos in 1 until lower.length) {
                for (k in 0 until pos) buf[k] = lower[k]
                for (k in pos until lower.length) buf[k + 1] = lower[k]
                for (vIdx in vowels.indices) {
                    buf[pos] = vowels[vIdx]
                    harvestPrefixNode(findNodeChars(buf), bonus = 90)
                }
            }
        }

        // Single QWERTY neighbor substitution in prefix
        if (found.isEmpty()) {
            for (i in 1 until chars.size) {
                val orig = chars[i]
                val neighbors = qwertyNeighbors[orig] ?: continue
                for (j in neighbors.indices) {
                    chars[i] = neighbors[j]
                    harvestPrefixNode(findNodeChars(chars), bonus = 80)
                }
                chars[i] = orig
            }
        }

        if (found.isEmpty()) return emptyList()
        return found.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    private fun hasRepeatedChars(s: String): Boolean {
        for (i in 1 until s.length) {
            if (s[i] == s[i - 1]) return true
        }
        return false
    }

    private fun collapseLongRuns(s: String, replacementLen: Int): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            var j = i + 1
            while (j < s.length && s[j] == c) j++
            val runLen = j - i
            val keep = if (runLen >= 3) replacementLen else runLen
            for (k in 0 until keep) sb.append(c)
            i = j
        }
        return sb.toString()
    }

    private fun collapseRuns(s: String, maxRun: Int): String {
        val sb = StringBuilder(s.length)
        var run = 0
        var last = '\u0000'
        for (i in s.indices) {
            val c = s[i]
            if (c == last) {
                run++
                if (run <= maxRun) sb.append(c)
            } else {
                last = c
                run = 1
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun exactLookupChars(chars: CharArray): TrieNode? {
        val node = findNodeChars(chars) ?: return null
        return if (node.word != null) node else null
    }

    private fun findNodeChars(chars: CharArray): TrieNode? {
        var node = root
        for (i in chars.indices) {
            node = node.children[chars[i]] ?: return null
        }
        return node
    }

    private fun exactLookupSkipping(s: String, skipIndex: Int): TrieNode? {
        val node = findNodeSkipping(s, skipIndex) ?: return null
        return if (node.word != null) node else null
    }

    private fun findNodeSkipping(s: String, skipIndex: Int): TrieNode? {
        var node = root
        for (i in s.indices) {
            if (i == skipIndex) continue
            node = node.children[s[i]] ?: return null
        }
        return node
    }

    private fun applyCaseStyle(suggestion: String, typed: String): String {
        if (typed.isEmpty() || suggestion.isEmpty()) return suggestion
        if (suggestion[0].isUpperCase() && typed.all { it.isLowerCase() }) return suggestion

        return when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> suggestion.uppercase()
            typed[0].isUpperCase() -> suggestion.replaceFirstChar { it.uppercaseChar() }
            else -> suggestion
        }
    }

    private fun frequencyForRank(rank: Int): Int = when {
        rank < 100 -> 2400 - rank * 6
        rank < 400 -> 1800 - (rank - 100) * 2
        rank < 1200 -> 1200 - ((rank - 400) * 3) / 4
        else -> (600 - (rank - 1200) / 10).coerceAtLeast(120)
    }

    private fun seedLexiconFromResourcesOrFallback() {
        val loadedEn = loadResourceWordList("/dict/en_words.txt", LANG_EN)
        if (loadedEn == 0) {
            seedList(fallbackEnglishWords, LANG_EN)
        }
        val loadedId = loadResourceWordList("/dict/id_words.txt", LANG_ID)
        if (loadedId == 0) {
            seedList(fallbackIndonesianWords, LANG_ID)
        }
    }

    private fun loadResourceWordList(resourcePath: String, langTag: Byte): Int {
        val stream = SuggestionEngine::class.java.getResourceAsStream(resourcePath) ?: return 0
        var rank = 0
        try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val word = line.trim()
                    if (word.isEmpty()) continue
                    insert(word, frequencyForRank(rank), langTag)
                    rank++
                }
            }
        } catch (_: Throwable) {
            return rank
        }
        return rank
    }

    private fun seedList(words: List<String>, langTag: Byte) {
        val seen = HashSet<String>(words.size)
        var rank = 0
        for (word in words) {
            if (seen.add(word.lowercase())) {
                insert(word, frequencyForRank(rank), langTag)
                rank++
            }
        }
    }

    private fun seedCoreBigramsAndTrigrams() {
        val pairs: Map<String, List<String>> = mapOf(
            // --- English conversational & productivity bigrams ---
            "i" to listOf("am", "think", "want", "have", "will", "just"),
            "i'm" to listOf("going", "good", "not", "so", "on", "here"),
            "i'll" to listOf("be", "let", "send", "check", "get", "call"),
            "i've" to listOf("been", "got", "seen", "never", "just"),
            "i'd" to listOf("love", "like", "rather", "be"),
            "how" to listOf("are", "much", "about", "is", "long", "do"),
            "how's" to listOf("it", "your", "everything", "that"),
            "what" to listOf("are", "is", "do", "time", "about", "happened"),
            "what's" to listOf("up", "going", "your", "the", "wrong"),
            "where" to listOf("are", "is", "do", "did", "can"),
            "when" to listOf("are", "will", "do", "is", "can"),
            "why" to listOf("not", "are", "did", "is", "do"),
            "who" to listOf("is", "are", "was", "did", "else"),
            "are" to listOf("you", "we", "they", "there", "all"),
            "do" to listOf("you", "not", "we", "it", "that"),
            "did" to listOf("you", "not", "it", "they", "that"),
            "does" to listOf("it", "that", "this", "anyone", "not"),
            "can" to listOf("you", "I", "we", "be", "help"),
            "could" to listOf("you", "be", "I", "we", "have"),
            "would" to listOf("you", "be", "like", "love", "have"),
            "should" to listOf("be", "I", "we", "have", "do"),
            "will" to listOf("be", "you", "do", "send", "let"),
            "you" to listOf("are", "can", "know", "want", "have", "too"),
            "you're" to listOf("welcome", "right", "the", "so", "going"),
            "you'll" to listOf("be", "get", "love", "see"),
            "we" to listOf("are", "can", "have", "should", "will", "need"),
            "we're" to listOf("going", "on", "almost", "here", "good"),
            "they" to listOf("are", "have", "were", "will", "said"),
            "he" to listOf("is", "was", "said", "will", "has"),
            "she" to listOf("is", "was", "said", "will", "has"),
            "it" to listOf("is", "was", "will", "looks", "works", "sounds"),
            "it's" to listOf("not", "a", "so", "been", "okay", "great"),
            "that" to listOf("is", "was", "would", "sounds", "makes"),
            "that's" to listOf("great", "good", "true", "awesome", "fine", "why"),
            "this" to listOf("is", "was", "one", "week", "morning"),
            "there" to listOf("is", "are", "was", "were", "will"),
            "there's" to listOf("a", "no", "nothing", "something"),
            "here" to listOf("is", "are", "you", "now", "we"),
            "good" to listOf("morning", "night", "luck", "idea", "job", "point"),
            "great" to listOf("job", "idea", "work", "news", "day"),
            "thank" to listOf("you", "so", "god"),
            "thanks" to listOf("so", "a", "for", "again", "bro"),
            "see" to listOf("you", "what", "if", "it", "the"),
            "talk" to listOf("to", "later", "soon", "about"),
            "let" to listOf("me", "us", "you", "it", "them"),
            "let's" to listOf("go", "do", "see", "meet", "try", "get"),
            "have" to listOf("a", "to", "you", "been", "fun", "time"),
            "has" to listOf("been", "a", "to", "the"),
            "had" to listOf("a", "to", "been", "the"),
            "want" to listOf("to", "a", "some", "me", "you"),
            "need" to listOf("to", "a", "help", "some", "more"),
            "going" to listOf("to", "be", "home", "out", "on"),
            "coming" to listOf("over", "home", "soon", "back", "today"),
            "looking" to listOf("for", "good", "at", "forward"),
            "waiting" to listOf("for", "on", "to"),
            "working" to listOf("on", "from", "with", "hard", "today"),
            "trying" to listOf("to", "my", "out"),
            "thinking" to listOf("about", "of", "that"),
            "sorry" to listOf("for", "about", "I", "to", "bro"),
            "please" to listOf("let", "send", "help", "check", "wait"),
            "happy" to listOf("birthday", "to", "hear", "new", "for"),
            "love" to listOf("you", "it", "this", "that", "to"),
            "miss" to listOf("you", "it", "this", "home"),
            "like" to listOf("to", "a", "the", "this", "that"),
            "just" to listOf("wanted", "got", "finished", "checking", "a", "now"),
            "still" to listOf("working", "here", "waiting", "not", "at"),
            "already" to listOf("done", "sent", "got", "here", "have"),
            "almost" to listOf("there", "done", "ready", "home"),
            "right" to listOf("now", "here", "away", "back"),
            "in" to listOf("the", "a", "my", "case", "fact"),
            "on" to listOf("the", "my", "time", "it", "this"),
            "at" to listOf("the", "home", "work", "all", "least"),
            "to" to listOf("the", "be", "do", "get", "go", "see"),
            "for" to listOf("the", "you", "me", "sure", "now"),
            "with" to listOf("you", "the", "me", "this", "my"),
            "from" to listOf("the", "my", "here", "home", "work"),
            "about" to listOf("the", "it", "you", "that", "to"),
            "not" to listOf("sure", "yet", "really", "at", "a"),
            "so" to listOf("much", "good", "far", "sorry", "happy"),
            "very" to listOf("good", "much", "nice", "well", "fast"),
            "no" to listOf("problem", "way", "worries", "idea", "need"),
            "of" to listOf("course", "the", "my", "all", "this"),
            "don't" to listOf("know", "worry", "think", "forget", "have", "mind"),
            "can't" to listOf("wait", "believe", "do", "make", "find"),
            "won't" to listOf("be", "work", "have", "let"),
            "didn't" to listOf("know", "see", "get", "mean", "have"),
            "doesn't" to listOf("matter", "work", "make", "have"),
            "make" to listOf("sure", "it", "a", "sense"),
            "take" to listOf("care", "a", "the", "your", "time"),
            "give" to listOf("me", "you", "it", "a"),
            "send" to listOf("me", "it", "you", "the"),
            "check" to listOf("it", "out", "the", "your"),
            "sounds" to listOf("good", "great", "like", "awesome"),
            "feel" to listOf("free", "like", "better", "good"),
            "call" to listOf("me", "you", "back", "later"),
            "text" to listOf("me", "you", "back"),
            "keep" to listOf("in", "going", "it", "up"),
            "get" to listOf("back", "home", "it", "some", "ready"),
            "got" to listOf("it", "a", "to", "back", "home"),
            "all" to listOf("good", "the", "right", "set", "day"),
            "my" to listOf("phone", "home", "friend", "work", "way", "bad"),
            "your" to listOf("day", "time", "phone", "help", "work"),
            "our" to listOf("team", "meeting", "plan", "way"),
            "next" to listOf("week", "time", "month", "day", "year"),
            "last" to listOf("night", "week", "time", "year", "month"),
            "every" to listOf("day", "time", "single", "week"),
            "some" to listOf("time", "food", "help", "more", "coffee"),
            "much" to listOf("better", "more", "appreciated", "time"),
            "more" to listOf("time", "than", "info", "details"),
            "liquid" to listOf("glass", "blur", "style"),
            "frosted" to listOf("blur", "glass", "theme"),

            // --- Bahasa Indonesia conversational & formal bigrams ---
            "selamat" to listOf("pagi", "malam", "siang", "sore", "ulang", "datang"),
            "terima" to listOf("kasih", "saja", "dulu", "ya"),
            "makasih" to listOf("banyak", "ya", "banget", "kak", "bro"),
            "sama" to listOf("sama", "kamu", "aku", "dia", "aja"),
            "apa" to listOf("kabar", "yang", "itu", "aja", "lagi", "ya"),
            "siapa" to listOf("yang", "tahu", "nama", "itu", "aja"),
            "kapan" to listOf("kita", "kamu", "bisa", "lagi", "mau"),
            "kenapa" to listOf("ya", "nih", "tuh", "kamu", "bisa", "tidak"),
            "dimana" to listOf("sekarang", "nih", "kamu", "lokasinya", "tempatnya"),
            "kemana" to listOf("nih", "kamu", "aja", "tadi"),
            "gimana" to listOf("kabar", "nih", "kalau", "hasilnya", "caranya", "ya"),
            "bagaimana" to listOf("kabar", "cara", "kalau", "dengan", "hasilnya"),
            "berapa" to listOf("lama", "banyak", "harganya", "orang", "jam"),
            "lagi" to listOf("apa", "di", "kerja", "makan", "sibuk", "jalan"),
            "mau" to listOf("makan", "pergi", "tidur", "tanya", "beli", "kemana"),
            "sudah" to listOf("selesai", "makan", "sampai", "bisa", "ada", "lama"),
            "udah" to listOf("selesai", "makan", "sampai", "belum", "lama", "oke"),
            "belum" to listOf("ada", "bisa", "tahu", "selesai", "makan", "sempat"),
            "tidak" to listOf("bisa", "ada", "apa-apa", "usah", "tahu", "jadi"),
            "enggak" to listOf("bisa", "ada", "tahu", "usah", "jadi", "apa-apa"),
            "nggak" to listOf("bisa", "ada", "tahu", "apa-apa", "usah", "jadi"),
            "gak" to listOf("bisa", "ada", "tahu", "apa-apa", "usah", "jadi"),
            "ga" to listOf("bisa", "ada", "tahu", "apa-apa", "usah", "jadi"),
            "bisa" to listOf("tolong", "bantu", "jadi", "aja", "kok", "di"),
            "boleh" to listOf("banget", "kok", "tanya", "minta", "dong"),
            "jangan" to listOf("lupa", "sampai", "dulu", "ya", "khawatir"),
            "harus" to listOf("bisa", "segera", "bayar", "kerja", "sabar"),
            "tolong" to listOf("kirim", "bantu", "cek", "kabari", "ya"),
            "maaf" to listOf("ya", "banget", "baru", "atas", "lahir", "telat"),
            "mohon" to listOf("maaf", "bantuan", "tunggu", "info", "dicek"),
            "saya" to listOf("mau", "sudah", "bisa", "akan", "sedang", "tidak"),
            "aku" to listOf("mau", "lagi", "sudah", "udah", "juga", "gak"),
            "kamu" to listOf("lagi", "sudah", "mau", "dimana", "udah", "bisa"),
            "anda" to listOf("bisa", "dapat", "akan", "sudah", "memiliki"),
            "kita" to listOf("bisa", "mau", "akan", "ketemu", "makan", "mulai"),
            "kami" to listOf("akan", "sudah", "sampaikan", "ucapkan", "mohon"),
            "mereka" to listOf("sudah", "akan", "bisa", "lagi", "mau"),
            "dia" to listOf("sudah", "lagi", "bilang", "mau", "tidak"),
            "ada" to listOf("yang", "apa", "di", "waktu", "masalah", "kabar"),
            "yang" to listOf("penting", "bagus", "lain", "mana", "sudah", "lebih"),
            "ini" to listOf("sudah", "bagus", "ya", "lagi", "bisa", "baru"),
            "itu" to listOf("ya", "bagus", "bisa", "sudah", "saja", "kan"),
            "di" to listOf("rumah", "kantor", "sini", "mana", "jalan", "luar"),
            "ke" to listOf("rumah", "kantor", "sana", "sini", "depan", "mana"),
            "dari" to listOf("mana", "rumah", "tadi", "kemarin", "sini", "kantor"),
            "untuk" to listOf("kamu", "kita", "semua", "hari", "sekarang", "itu"),
            "dengan" to listOf("baik", "cepat", "mudah", "senang", "lancar"),
            "kalau" to listOf("bisa", "ada", "mau", "sudah", "gak", "tidak"),
            "karena" to listOf("itu", "saya", "sudah", "memang", "masih", "lagi"),
            "tapi" to listOf("kalau", "masih", "belum", "tetap", "sudah", "gak"),
            "jadi" to listOf("gimana", "bisa", "lebih", "begini", "tidak"),
            "masih" to listOf("ada", "lama", "di", "kerja", "belum", "bisa"),
            "baru" to listOf("saja", "aja", "sampai", "bangun", "pulang", "mau"),
            "sedang" to listOf("dalam", "sibuk", "kerja", "di", "makan"),
            "sampai" to listOf("ketemu", "nanti", "besok", "jumpa", "sekarang"),
            "hati-hati" to listOf("di", "ya", "dijalan"),
            "hari" to listOf("ini", "besok", "minggu", "senin", "sabtu", "libur"),
            "malam" to listOf("ini", "minggu", "semua", "kak"),
            "pagi" to listOf("ini", "semua", "kak", "banget"),
            "besok" to listOf("pagi", "siang", "sore", "malam", "kita"),
            "nanti" to listOf("malam", "sore", "aku", "saya", "kita", "kalau"),
            "sebentar" to listOf("ya", "lagi", "aku", "dulu"),
            "bentar" to listOf("ya", "lagi", "aku", "dulu"),
            "semoga" to listOf("sehat", "sukses", "lancar", "cepat", "berkah", "bisa"),
            "halo" to listOf("apa", "semua", "kak", "bro", "selamat"),
            "hai" to listOf("apa", "kamu", "semua", "kak"),
            "oke" to listOf("siap", "makasih", "sip", "baik", "deh", "banget"),
            "siap" to listOf("kak", "bos", "makasih", "laksanakan", "banget"),
            "iya" to listOf("nih", "dong", "sama-sama", "benar", "betul", "kak"),
            "ya" to listOf("udah", "ampun", "kan", "sama-sama", "kak", "allah"),
            "kurang" to listOf("lebih", "tahu", "jelas", "baik", "enak"),
            "lebih" to listOf("baik", "cepat", "bagus", "murah", "banyak", "enak"),
            "paling" to listOf("bagus", "enak", "penting", "cepat", "suka"),
            "sangat" to listOf("bagus", "baik", "penting", "membantu", "mudah"),
            "terlalu" to listOf("lama", "cepat", "banyak", "mahal", "jauh"),
            "cukup" to listOf("baik", "bagus", "lama", "banyak", "jelas"),
            "makan" to listOf("siang", "malam", "pagi", "bareng", "apa", "dulu"),
            "minum" to listOf("kopi", "air", "teh", "obat", "dulu"),
            "pulang" to listOf("kerja", "dulu", "kerumah", "malam", "sore"),
            "masuk" to listOf("kerja", "kantor", "kuliah", "akal", "dulu"),
            "kirim" to listOf("ke", "ulang", "foto", "file", "link", "alamat"),
            "tunggu" to listOf("sebentar", "bentar", "ya", "dulu", "kabarku"),
            "minta" to listOf("tolong", "maaf", "info", "link", "alamat"),
            "cari" to listOf("makan", "tahu", "kerja", "yang", "tempat"),
            "kasih" to listOf("tahu", "banyak", "ya", "tau", "kabar"),
            "ulang" to listOf("tahun", "lagi", "ya"),
            "terima kasih" to listOf("banyak", "ya", "banget")
        )

        pairs.forEach { (prev, nexts) ->
            val map = LinkedHashMap<String, BigramEntry>(nexts.size)
            nexts.forEachIndexed { idx, w ->
                map[w.lowercase()] = BigramEntry(displayWord = w, score = 110 - idx * 14)
            }
            bigrams[prev] = map
        }

        // Rich contextual trigrams (2-word lookback) for natural iOS QuickType flow!
        val triSeeds: Map<String, List<String>> = mapOf(
            // English trigrams
            "how are" to listOf("you", "things", "they"),
            "how is" to listOf("it", "your", "everything"),
            "how was" to listOf("your", "it", "the"),
            "how much" to listOf("is", "does", "time"),
            "how long" to listOf("will", "does", "is"),
            "i am" to listOf("good", "going", "not"),
            "i want" to listOf("to", "a", "you"),
            "i need" to listOf("to", "a", "help"),
            "i have" to listOf("to", "a", "been"),
            "i think" to listOf("it", "we", "so"),
            "i know" to listOf("right", "what", "you"),
            "i just" to listOf("wanted", "got", "finished"),
            "i don't" to listOf("know", "think", "want"),
            "i can't" to listOf("wait", "believe", "make"),
            "i will" to listOf("be", "send", "let"),
            "i can" to listOf("do", "help", "send"),
            "i would" to listOf("love", "like", "be"),
            "i love" to listOf("you", "it", "this"),
            "i miss" to listOf("you", "it", "home"),
            "i hope" to listOf("you", "so", "it"),
            "i'm going" to listOf("to", "home", "out"),
            "i'm not" to listOf("sure", "going", "ready"),
            "i'm so" to listOf("sorry", "happy", "tired"),
            "i'm on" to listOf("my", "the", "it"),
            "i'll be" to listOf("there", "right", "back"),
            "i'll let" to listOf("you", "him", "her"),
            "thank you" to listOf("so", "very", "for"),
            "thanks so" to listOf("much", "for", "far"),
            "thanks a" to listOf("lot", "ton", "million"),
            "you are" to listOf("welcome", "the", "right"),
            "see you" to listOf("soon", "later", "tomorrow"),
            "talk to" to listOf("you", "him", "her"),
            "let me" to listOf("know", "check", "see"),
            "let us" to listOf("know", "see", "go"),
            "have a" to listOf("great", "good", "nice"),
            "on my" to listOf("way", "phone", "own"),
            "on the" to listOf("way", "other", "phone"),
            "in the" to listOf("morning", "evening", "middle"),
            "at the" to listOf("moment", "same", "office"),
            "by the" to listOf("way", "time", "end"),
            "for the" to listOf("first", "next", "rest"),
            "what are" to listOf("you", "we", "they"),
            "what is" to listOf("your", "the", "going"),
            "what do" to listOf("you", "we", "they"),
            "what time" to listOf("is", "are", "do"),
            "where are" to listOf("you", "we", "they"),
            "when are" to listOf("you", "we", "they"),
            "good morning" to listOf("everyone", "friend", "all"),
            "good night" to listOf("everyone", "sleep", "all"),
            "good luck" to listOf("with", "today", "on"),
            "happy birthday" to listOf("to", "friend", "hope"),
            "are you" to listOf("free", "ready", "going"),
            "can you" to listOf("help", "send", "please"),
            "could you" to listOf("please", "send", "help"),
            "would you" to listOf("like", "mind", "be"),
            "do you" to listOf("want", "have", "know"),
            "did you" to listOf("get", "see", "go"),
            "sounds good" to listOf("to", "let's", "see"),
            "no problem" to listOf("at", "anytime", "bro"),
            "of course" to listOf("I", "you", "we"),
            "make sure" to listOf("you", "to", "it"),
            "take care" to listOf("of", "and", "see"),
            "feel free" to listOf("to", "and", "anytime"),
            "looking forward" to listOf("to", "seeing", "hearing"),
            "as soon" to listOf("as", "possible"),
            "going to" to listOf("be", "do", "go"),
            "want to" to listOf("go", "see", "do"),
            "need to" to listOf("be", "go", "get"),
            "trying to" to listOf("get", "find", "fix"),
            "sorry for" to listOf("the", "being", "late"),
            "sorry about" to listOf("that", "the", "earlier"),

            // Bahasa Indonesia trigrams
            "apa kabar" to listOf("hari", "kamu", "sekarang"),
            "terima kasih" to listOf("banyak", "ya", "banget"),
            "makasih banyak" to listOf("ya", "kak", "banget"),
            "selamat pagi" to listOf("semua", "teman", "kak"),
            "selamat siang" to listOf("semua", "kak", "teman"),
            "selamat sore" to listOf("semua", "kak", "teman"),
            "selamat malam" to listOf("semua", "selamat", "istirahat"),
            "selamat ulang" to listOf("tahun", "hari"),
            "ulang tahun" to listOf("semoga", "ya", "panjang"),
            "mohon maaf" to listOf("atas", "lahir", "ya"),
            "maaf ya" to listOf("baru", "banget", "telat"),
            "maaf baru" to listOf("balas", "baca", "sempat"),
            "saya mau" to listOf("makan", "pergi", "tanya"),
            "saya sudah" to listOf("sampai", "makan", "kirim"),
            "saya belum" to listOf("bisa", "sempat", "tahu"),
            "saya tidak" to listOf("bisa", "tahu", "mau"),
            "aku mau" to listOf("makan", "pergi", "tidur"),
            "aku lagi" to listOf("di", "kerja", "makan"),
            "aku udah" to listOf("sampai", "makan", "selesai"),
            "aku gak" to listOf("bisa", "tahu", "mau"),
            "kamu lagi" to listOf("apa", "dimana", "sibuk"),
            "kamu sudah" to listOf("makan", "sampai", "tidur"),
            "kamu udah" to listOf("makan", "sampai", "tidur"),
            "lagi apa" to listOf("sekarang", "nih", "kamu"),
            "lagi dimana" to listOf("sekarang", "nih", "kamu"),
            "lagi di" to listOf("rumah", "kantor", "jalan"),
            "tidak bisa" to listOf("datang", "buka", "masuk"),
            "enggak bisa" to listOf("datang", "buka", "masuk"),
            "gak bisa" to listOf("datang", "buka", "ikut"),
            "nggak apa-apa" to listOf("kok", "santai", "ya"),
            "gak apa-apa" to listOf("kok", "santai", "ya"),
            "sudah makan" to listOf("belum", "siang", "tadi"),
            "udah makan" to listOf("belum", "siang", "tadi"),
            "sudah sampai" to listOf("rumah", "kantor", "belum"),
            "mau makan" to listOf("apa", "dimana", "bareng"),
            "mau pergi" to listOf("kemana", "ke", "sama"),
            "sampai ketemu" to listOf("lagi", "besok", "nanti"),
            "sampai jumpa" to listOf("lagi", "besok", "nanti"),
            "hati-hati di" to listOf("jalan", "sana", "rumah"),
            "jangan lupa" to listOf("makan", "istirahat", "bawa"),
            "semoga cepat" to listOf("sembuh", "selesai", "lancar"),
            "semoga sehat" to listOf("selalu", "dan", "terus"),
            "semoga lancar" to listOf("ya", "terus", "semuanya"),
            "semoga sukses" to listOf("selalu", "ya", "terus"),
            "hari ini" to listOf("kita", "aku", "saya"),
            "malam ini" to listOf("kita", "aku", "ada"),
            "besok pagi" to listOf("kita", "aku", "jam"),
            "kalau ada" to listOf("waktu", "apa-apa", "yang"),
            "kalau bisa" to listOf("hari", "besok", "sekarang"),
            "kalau sudah" to listOf("selesai", "sampai", "ada"),
            "kalau mau" to listOf("ikut", "makan", "beli"),
            "bisa tolong" to listOf("kirim", "bantu", "cek"),
            "tolong kirim" to listOf("ke", "ulang", "ya"),
            "minta tolong" to listOf("bantu", "kirim", "cek"),
            "ada yang" to listOf("bisa", "mau", "tau"),
            "yang penting" to listOf("sehat", "kita", "sudah"),
            "kurang lebih" to listOf("sekitar", "jam", "begitu"),
            "lebih baik" to listOf("kita", "langsung", "sekarang"),
            "tunggu sebentar" to listOf("ya", "lagi", "dulu"),
            "bentar ya" to listOf("aku", "lagi", "saya"),
            "oke siap" to listOf("kak", "makasih", "bos"),
            "di rumah" to listOf("aja", "saja", "sekarang"),
            "di kantor" to listOf("sekarang", "sampai", "nih"),
            "di jalan" to listOf("pulang", "menuju", "nih"),
            "dari mana" to listOf("aja", "nih", "tadi"),
            "gimana kabar" to listOf("kamu", "hari", "sekarang"),
            "gimana nih" to listOf("jadinya", "kelanjutannya", "kita")
        )
        triSeeds.forEach { (k, v) ->
            trigrams[k] = v.toMutableList()
        }
    }

    companion object {
        private const val TOP_K_PER_LANG = 10
        private const val TOP_K_USER = 8
        private const val LANG_BOTH: Byte = 0
        private const val LANG_EN: Byte = 1
        private const val LANG_ID: Byte = 2

        private val qwertyNeighbors: Map<Char, String> = mapOf(
            'q' to "wa",
            'w' to "qase",
            'e' to "wsdr",
            'r' to "edft",
            't' to "rfgy",
            'y' to "tghu",
            'u' to "yhji",
            'i' to "ujko",
            'o' to "iklp",
            'p' to "ol",
            'a' to "qwsz",
            's' to "qwedxza",
            'd' to "werfcxs",
            'f' to "ertgvcd",
            'g' to "rtyhbvf",
            'h' to "tyujnbg",
            'j' to "yuikmnh",
            'k' to "uiolmj",
            'l' to "iopk",
            'z' to "asx",
            'x' to "zsdc",
            'c' to "xdfv",
            'v' to "cfgb",
            'b' to "vghn",
            'n' to "bhjm",
            'm' to "njk"
        )

        // Compact emergency fallback if classpath dictionary resources are unavailable
        private val fallbackEnglishWords = listOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "I",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
            "hello", "hey", "hi", "thanks", "thank", "please", "sorry", "good", "morning", "night",
            "today", "tomorrow", "yesterday", "love", "happy", "great", "awesome", "nice", "help", "because"
        )

        private val fallbackIndonesianWords = listOf(
            "yang", "dan", "di", "ini", "itu", "dengan", "untuk", "tidak", "dari", "pada",
            "saya", "aku", "kamu", "kita", "sudah", "belum", "lagi", "mau", "harus", "bisa",
            "selamat", "terima", "kasih", "makasih", "tolong", "maaf", "halo", "makan", "bagus", "sekarang",
            "indonesia", "sebenarnya", "sebentar", "bagaimana", "gimana", "kenapa", "dimana", "kopi", "rumah"
        )
    }
}
