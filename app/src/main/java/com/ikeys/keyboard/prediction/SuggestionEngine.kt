package com.ikeys.keyboard.prediction

/**
 * Ultra-fast bilingual (English + Bahasa Indonesia) QuickType suggestion & typo-correction engine.
 * Backed by a compact Trie with sub-millisecond prefix & 1-edit proximity lookup.
 */
data class SuggestionItem(
    val text: String,
    val isLiteral: Boolean = false,
    val isAutoCorrectCandidate: Boolean = false
)

class SuggestionEngine {

    private class TrieNode {
        val children = HashMap<Char, TrieNode>(4)
        var word: String? = null
        var frequency: Int = 0
    }

    private val root = TrieNode()
    private val contractions: Map<String, String> = mapOf(
        "im" to "I'm",
        "ive" to "I've",
        "id" to "I'd",
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
        "wouldnt" to "wouldn't",
        "couldnt" to "couldn't",
        "shouldnt" to "shouldn't",
        "youre" to "you're",
        "youve" to "you've",
        "youll" to "you'll",
        "thats" to "that's",
        "theres" to "there's",
        "whats" to "what's",
        "heres" to "here's",
        "lets" to "let's",
        "theyre" to "they're",
        "were" to "we're",
        "hes" to "he's",
        "shes" to "she's",
        "teh" to "the",
        "adn" to "and",
        "recieve" to "receive",
        "seperate" to "separate",
        "definately" to "definitely",
        // Common Indonesian shorthand expansions
        "yg" to "yang",
        "dgn" to "dengan",
        "utk" to "untuk",
        "sdh" to "sudah",
        "blm" to "belum",
        "krn" to "karena",
        "tdk" to "tidak",
        "gak" to "enggak",
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
        "mksh" to "makasih"
    )

    private val defaultStarters = listOf("I", "The", "Halo")

    init {
        seedCoreLexicon()
    }

    fun learnWord(rawWord: String, boost: Int = 25) {
        val cleaned = rawWord.trim()
        if (cleaned.length < 2 || cleaned.length > 28) return
        if (!cleaned.all { it.isLetter() || it == '\'' || it == '-' }) return
        insert(cleaned, boost)
    }

    fun suggest(currentWord: String, maxResults: Int = 3): List<SuggestionItem> {
        if (currentWord.isBlank()) {
            return defaultStarters.take(maxResults).map { SuggestionItem(text = it) }
        }

        val lower = currentWord.lowercase()
        val exactContraction = contractions[lower]

        val prefixMatches = collectPrefixMatches(lower, limit = 12)
        val fuzzyMatches = if (prefixMatches.size < 3 && lower.length >= 3) {
            collectNearbyTypoMatches(lower, limit = 6)
        } else {
            emptyList()
        }

        val ranked = ArrayList<String>(6)
        val seen = HashSet<String>()

        fun addCandidate(word: String) {
            val styled = applyCaseStyle(word, currentWord)
            if (seen.add(styled.lowercase())) {
                ranked.add(styled)
            }
        }

        if (exactContraction != null) {
            addCandidate(exactContraction)
        }

        prefixMatches.sortedByDescending { it.second }.forEach { (w, _) ->
            if (!w.equals(lower, ignoreCase = true)) {
                addCandidate(w)
            }
        }

        fuzzyMatches.sortedByDescending { it.second }.forEach { (w, _) ->
            addCandidate(w)
        }

        val exactKnown = exactLookup(lower) != null

        // Authentic iOS QuickType 3-slot bar:
        // Slot 0: Literal typed word in quotes (if autocorrect candidate exists) or secondary
        // Slot 1 (Center): Primary prediction / autocorrect candidate
        // Slot 2 (Right): Next best completion
        val topPrimary = ranked.firstOrNull()
        val shouldAutoCorrect = topPrimary != null &&
            (!exactKnown || exactContraction != null) &&
            !topPrimary.equals(currentWord, ignoreCase = true)

        val slots = ArrayList<SuggestionItem>(maxResults)
        if (shouldAutoCorrect && topPrimary != null) {
            slots.add(SuggestionItem(text = currentWord, isLiteral = true))
            slots.add(SuggestionItem(text = topPrimary, isAutoCorrectCandidate = true))
            ranked.getOrNull(1)?.let { slots.add(SuggestionItem(text = it)) }
        } else {
            if (topPrimary != null) {
                slots.add(SuggestionItem(text = currentWord, isLiteral = !exactKnown))
                slots.add(SuggestionItem(text = topPrimary))
                ranked.getOrNull(1)?.let { slots.add(SuggestionItem(text = it)) }
            } else {
                slots.add(SuggestionItem(text = currentWord, isLiteral = true))
            }
        }

        return slots.take(maxResults)
    }

    /**
     * Returns the autocorrect replacement if the middle slot is an active autocorrect candidate.
     */
    fun autoCorrectFor(currentWord: String): String? {
        if (currentWord.length < 2) return null
        val suggestions = suggest(currentWord, maxResults = 3)
        return suggestions.firstOrNull { it.isAutoCorrectCandidate }?.text
    }

    private fun insert(word: String, frequency: Int) {
        var node = root
        val lower = word.lowercase()
        for (ch in lower) {
            node = node.children.getOrPut(ch) { TrieNode() }
        }
        if (node.word == null || frequency > node.frequency) {
            node.word = word
        }
        node.frequency += frequency
    }

    private fun exactLookup(lowerWord: String): TrieNode? {
        var node = root
        for (ch in lowerWord) {
            node = node.children[ch] ?: return null
        }
        return if (node.word != null) node else null
    }

    private fun collectPrefixMatches(prefix: String, limit: Int): List<Pair<String, Int>> {
        var node = root
        for (ch in prefix) {
            node = node.children[ch] ?: return emptyList()
        }
        val collected = ArrayList<Pair<String, Int>>(limit * 2)
        dfsCollect(node, collected, maxNodes = 80)
        return collected.sortedByDescending { it.second }.take(limit)
    }

    private fun dfsCollect(node: TrieNode, out: MutableList<Pair<String, Int>>, maxNodes: Int) {
        if (out.size >= maxNodes) return
        node.word?.let { out.add(it to node.frequency) }
        for (child in node.children.values) {
            if (out.size >= maxNodes) return
            dfsCollect(child, out, maxNodes)
        }
    }

    /**
     * Lightweight single-character edit correction (adjacent swap & QWERTY neighbor substitution).
     */
    private fun collectNearbyTypoMatches(lower: String, limit: Int): List<Pair<String, Int>> {
        val found = LinkedHashMap<String, Int>()

        // 1. Adjacent character transpositions (e.g. "hte" -> "the", "syaa" -> "saya")
        for (i in 0 until lower.length - 1) {
            val chars = lower.toCharArray()
            val tmp = chars[i]
            chars[i] = chars[i + 1]
            chars[i + 1] = tmp
            val candidate = String(chars)
            exactLookup(candidate)?.let { node ->
                node.word?.let { found[it] = node.frequency + 40 }
            }
        }

        // 2. QWERTY keyboard proximity single-character substitutions
        for (i in lower.indices) {
            val neighbors = qwertyNeighbors[lower[i]] ?: continue
            val chars = lower.toCharArray()
            for (n in neighbors) {
                chars[i] = n
                val candidate = String(chars)
                exactLookup(candidate)?.let { node ->
                    node.word?.let { found[it] = maxOf(found[it] ?: 0, node.frequency + 20) }
                }
            }
        }

        // 3. Single extra-letter deletion (e.g. "helllo" -> "hello")
        if (lower.length >= 4) {
            for (i in lower.indices) {
                val candidate = lower.removeRange(i, i + 1)
                exactLookup(candidate)?.let { node ->
                    node.word?.let { found[it] = maxOf(found[it] ?: 0, node.frequency + 10) }
                }
            }
        }

        return found.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    private fun applyCaseStyle(suggestion: String, typed: String): String {
        if (typed.isEmpty() || suggestion.isEmpty()) return suggestion
        // Keep intentional uppercase like "I'm"
        if (suggestion[0].isUpperCase() && typed.all { it.isLowerCase() }) return suggestion

        return when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> suggestion.uppercase()
            typed[0].isUpperCase() -> suggestion.replaceFirstChar { it.uppercaseChar() }
            else -> suggestion
        }
    }

    private fun seedCoreLexicon() {
        // High-frequency English + Bahasa Indonesia core words ordered by frequency tier
        val coreWords = listOf(
            // English Top Core
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "I",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
            "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
            "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
            "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
            "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
            "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
            "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
            "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
            "hello", "hey", "hi", "thanks", "thank", "please", "sorry", "great", "awesome", "nice",
            "good", "morning", "afternoon", "evening", "night", "today", "tomorrow", "yesterday", "soon", "later",
            "sure", "okay", "yes", "yeah", "maybe", "really", "actually", "always", "never", "still",
            "right", "left", "here", "where", "why", "need", "help", "love", "happy", "best",
            "phone", "message", "keyboard", "Android", "iPhone", "iKeys", "app", "update", "send", "call",
            "meeting", "ready", "done", "waiting", "coming", "going", "working", "home", "office", "friend",
            "family", "food", "coffee", "water", "music", "video", "photo", "check", "start", "stop",
            "open", "close", "save", "share", "search", "find", "play", "watch", "read", "write",
            "change", "create", "build", "fast", "smooth", "clean", "light", "dark", "theme", "sound",
            "settings", "screen", "battery", "network", "email", "address", "number", "password", "account", "login",
            "beautiful", "wonderful", "perfect", "amazing", "definitely", "probably", "everything", "something", "nothing", "everyone",
            "someone", "anyone", "understand", "remember", "believe", "different", "important", "together", "already", "almost",
            "enough", "through", "between", "without", "before", "around", "during", "though", "should", "might",
            // Bahasa Indonesia Top Core
            "yang", "dan", "di", "ini", "itu", "dengan", "untuk", "tidak", "dari", "pada",
            "akan", "juga", "bisa", "ada", "saya", "aku", "kamu", "anda", "kita", "kami",
            "mereka", "dia", "sudah", "belum", "lagi", "mau", "harus", "dapat", "jadi", "sama",
            "kalau", "jika", "karena", "tapi", "tetapi", "atau", "seperti", "lebih", "sangat", "banget",
            "semua", "banyak", "sedikit", "hanya", "cuma", "masih", "baru", "lama", "besar", "kecil",
            "baik", "bagus", "keren", "mantap", "enak", "cepat", "ringan", "halus", "nyaman", "mudah",
            "susah", "sulit", "benar", "salah", "betul", "jelas", "pasti", "mungkin", "memang", "sekarang",
            "nanti", "besok", "kemarin", "hari", "pagi", "siang", "sore", "malam", "minggu", "bulan",
            "tahun", "waktu", "jam", "menit", "detik", "halo", "hai", "selamat", "terima", "kasih",
            "makasih", "sama-sama", "tolong", "maaf", "permisi", "silakan", "oke", "siap", "boleh", "jangan",
            "apa", "siapa", "kapan", "dimana", "kenapa", "mengapa", "bagaimana", "gimana", "berapa", "mana",
            "kabar", "sehat", "kerja", "kantor", "rumah", "jalan", "makan", "minum", "tidur", "bangun",
            "pergi", "pulang", "datang", "tunggu", "sebentar", "langsung", "terus", "lalu", "kemudian", "setelah",
            "sebelum", "ketika", "saat", "sambil", "sampai", "hingga", "tentang", "soal", "buat", "bikin",
            "ambil", "kasih", "kirim", "baca", "tulis", "lihat", "dengar", "bicara", "bilang", "tanya",
            "jawab", "cari", "beli", "bayar", "pesan", "buka", "tutup", "mulai", "selesai", "coba",
            "pakai", "gunakan", "simpan", "hapus", "ubah", "ganti", "tambah", "kurang", "masuk", "keluar",
            "orang", "teman", "keluarga", "anak", "ibu", "ayah", "kakak", "adik", "uang", "harga",
            "tempat", "kota", "negara", "dunia", "hidup", "hati", "cinta", "sayang", "senang", "bahagia",
            "sedih", "marah", "takut", "capek", "lelah", "sibuk", "santai", "penting", "khusus", "biasa",
            "pertama", "kedua", "ketiga", "terakhir", "lain", "lainnya", "sendiri", "bersama", "selalu", "sering",
            "jarang", "pernah", "enggak", "nggak", "bukan", "tanpa", "antara", "dalam", "luar", "atas",
            "bawah", "depan", "belakang", "kanan", "kiri", "dekat", "jauh", "tinggi", "rendah", "panjang",
            "pendek", "aplikasi", "ponsel", "layar", "suara", "getar", "tema", "pengaturan", "tombol", "huruf"
        )

        val total = coreWords.size
        coreWords.forEachIndexed { idx, word ->
            // Earlier words in each block get higher base frequency
            val score = (total - idx) + 100
            insert(word, score)
        }
    }

    companion object {
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
    }
}
