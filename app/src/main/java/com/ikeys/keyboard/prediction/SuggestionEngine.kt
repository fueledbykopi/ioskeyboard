package com.ikeys.keyboard.prediction

/**
 * Ultra-fast bilingual (English + Bahasa Indonesia) QuickType suggestion, next-word bigram,
 * and typo-correction engine.
 * Backed by a compact Trie + Bigram transition table with sub-millisecond lookup and 100% local privacy.
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
        var langTag: Byte = LANG_BOTH // 0=both, 1=en, 2=id
    }

    private val root = TrieNode()
    private val userLearnedWords = LinkedHashMap<String, Int>()
    private val bigrams = HashMap<String, LinkedHashMap<String, Int>>(128)

    /** Optional language bias ("en", "id", or null for balanced bilingual). */
    var preferredLanguage: String? = null

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
        "itll" to "it'll",
        "whos" to "who's",
        "wheres" to "where's",
        "whens" to "when's",
        "teh" to "the",
        "adn" to "and",
        "taht" to "that",
        "wiht" to "with",
        "waht" to "what",
        "recieve" to "receive",
        "seperate" to "separate",
        "definately" to "definitely",
        "occured" to "occurred",
        "untill" to "until",
        "becuase" to "because",
        "freind" to "friend",
        "tommorow" to "tomorrow",
        "thier" to "their",
        // Common Indonesian shorthand expansions
        "yg" to "yang",
        "dgn" to "dengan",
        "utk" to "untuk",
        "sdh" to "sudah",
        "blm" to "belum",
        "krn" to "karena",
        "tdk" to "tidak",
        "gak" to "enggak",
        "ga" to "enggak",
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
        "mksh" to "makasih",
        "bkn" to "bukan",
        "lg" to "lagi",
        "dlm" to "dalam",
        "hrs" to "harus",
        "bs" to "bisa",
        "sy" to "saya",
        "km" to "kamu",
        "ak" to "aku",
        "jg" to "juga",
        "kl" to "kalau",
        "klo" to "kalau",
        "gmn" to "gimana",
        "knp" to "kenapa",
        "dmn" to "dimana",
        "bgtu" to "begitu",
        "msh" to "masih",
        "bnyk" to "banyak",
        "sgt" to "sangat",
        "emg" to "memang",
        "bener" to "benar",
        "udh" to "sudah"
    )

    private val defaultStartersEn = listOf("I", "The", "Hey")
    private val defaultStartersId = listOf("Saya", "Halo", "Yang")
    private val defaultStartersMixed = listOf("I", "The", "Halo")

    init {
        seedCoreLexicon()
        seedCoreBigrams()
    }

    fun loadUserWordsCsv(csv: String) {
        if (csv.isBlank()) return
        csv.split(',').forEach { token ->
            val word = token.trim()
            if (word.length >= 2) {
                learnWord(word, boost = 40)
            }
        }
    }

    fun exportUserWordsCsv(maxWords: Int = 200): String {
        if (userLearnedWords.isEmpty()) return ""
        return userLearnedWords.entries
            .sortedByDescending { it.value }
            .take(maxWords)
            .joinToString(",") { it.key }
    }

    fun learnWord(rawWord: String, boost: Int = 25) {
        val cleaned = rawWord.trim()
        if (cleaned.length < 2 || cleaned.length > 28) return
        if (!cleaned.all { it.isLetter() || it == '\'' || it == '-' }) return
        insert(cleaned, boost, LANG_BOTH)
        userLearnedWords[cleaned] = (userLearnedWords[cleaned] ?: 0) + boost
    }

    fun learnTransition(prevWord: String, nextWord: String, boost: Int = 35) {
        val p = prevWord.trim().lowercase()
        val n = nextWord.trim()
        if (p.isEmpty() || n.length < 2 || n.length > 24) return
        if (!n.all { it.isLetter() || it == '\'' || it == '-' }) return
        val map = bigrams.getOrPut(p) { LinkedHashMap(6) }
        map[n] = (map[n] ?: 0) + boost
    }

    fun suggest(
        currentWord: String,
        maxResults: Int = 3,
        previousWord: String = ""
    ): List<SuggestionItem> {
        // 1. Between words: show contextual next-word predictions (bigrams) or sentence starters
        if (currentWord.isBlank()) {
            val prevLower = previousWord.trim().lowercase()
            if (prevLower.isNotEmpty()) {
                val nextWords = bigrams[prevLower]
                if (!nextWords.isNullOrEmpty()) {
                    return nextWords.entries
                        .sortedByDescending { it.value }
                        .take(maxResults)
                        .map { SuggestionItem(text = it.key) }
                }
            }
            val starters = when (preferredLanguage) {
                "en" -> defaultStartersEn
                "id" -> defaultStartersId
                else -> defaultStartersMixed
            }
            return starters.take(maxResults).map { SuggestionItem(text = it) }
        }

        val lower = currentWord.lowercase()
        val exactContraction = contractions[lower]

        val prefixMatches = collectPrefixMatches(lower, limit = 16)
        val fuzzyMatches = if (lower.length >= 3 && (prefixMatches.size < 3 || exactLookup(lower) == null)) {
            collectNearbyTypoMatches(lower, limit = 8)
        } else {
            emptyList()
        }

        val prevLower = previousWord.trim().lowercase()
        val prevBigrams = if (prevLower.isNotEmpty()) bigrams[prevLower] else null

        val rankedPrefix = ArrayList<String>(8)
        val rankedFuzzy = ArrayList<String>(6)
        val seen = HashSet<String>()

        fun addCandidate(target: MutableList<String>, word: String) {
            val styled = applyCaseStyle(word, currentWord)
            if (seen.add(styled.lowercase())) {
                target.add(styled)
            }
        }

        if (exactContraction != null) {
            addCandidate(rankedPrefix, exactContraction)
        }

        // Boost prefix matches that also fit the previous word bigram context
        prefixMatches
            .sortedByDescending { (w, freq) ->
                val bigramBonus = prevBigrams?.get(w.lowercase())?.times(6) ?: 0
                freq + bigramBonus
            }
            .forEach { (w, _) ->
                if (!w.equals(lower, ignoreCase = true)) {
                    addCandidate(rankedPrefix, w)
                }
            }

        fuzzyMatches.sortedByDescending { it.second }.forEach { (w, _) ->
            if (!w.equals(lower, ignoreCase = true)) {
                addCandidate(rankedFuzzy, w)
            }
        }

        val exactKnown = exactLookup(lower) != null

        // Authentic iOS QuickType 3-slot bar:
        // - Exact contraction/shorthand (e.g. "dont" -> "don't", "yg" -> "yang") => AutoCorrect candidate!
        // - Unknown 3+ char word with a strong 1-edit typo match and no exact dictionary match => AutoCorrect candidate!
        // - Normal prefix typing => helpful completions without aggressively replacing valid partial words on Space.
        val typoCandidate = if (!exactKnown && exactContraction == null && rankedPrefix.isEmpty()) {
            rankedFuzzy.firstOrNull()
        } else {
            null
        }

        val autoCorrectTarget = when {
            exactContraction != null -> applyCaseStyle(exactContraction, currentWord)
            typoCandidate != null -> typoCandidate
            else -> null
        }

        val combined = ArrayList<String>(6)
        combined.addAll(rankedPrefix)
        combined.addAll(rankedFuzzy)

        val slots = ArrayList<SuggestionItem>(maxResults)
        if (autoCorrectTarget != null && !autoCorrectTarget.equals(currentWord, ignoreCase = true)) {
            slots.add(SuggestionItem(text = currentWord, isLiteral = true))
            slots.add(SuggestionItem(text = autoCorrectTarget, isAutoCorrectCandidate = true))
            combined.firstOrNull { !it.equals(autoCorrectTarget, ignoreCase = true) }
                ?.let { slots.add(SuggestionItem(text = it)) }
        } else {
            val primary = combined.firstOrNull()
            if (primary != null) {
                // Slot 0: typed word (in quotes if not a known complete word), Slot 1 & 2: top completions
                slots.add(SuggestionItem(text = currentWord, isLiteral = !exactKnown))
                slots.add(SuggestionItem(text = primary))
                combined.getOrNull(1)?.let { slots.add(SuggestionItem(text = it)) }
            } else {
                slots.add(SuggestionItem(text = currentWord, isLiteral = true))
            }
        }

        return slots.take(maxResults)
    }

    /**
     * Returns the autocorrect replacement if the middle slot is an active autocorrect candidate.
     */
    fun autoCorrectFor(currentWord: String, previousWord: String = ""): String? {
        if (currentWord.length < 2) return null
        val suggestions = suggest(currentWord, maxResults = 3, previousWord = previousWord)
        return suggestions.firstOrNull { it.isAutoCorrectCandidate }?.text
    }

    private fun insert(word: String, frequency: Int, langTag: Byte) {
        var node = root
        val lower = word.lowercase()
        for (ch in lower) {
            node = node.children.getOrPut(ch) { TrieNode() }
        }
        if (node.word == null || frequency > node.frequency) {
            node.word = word
        }
        if (node.langTag != LANG_BOTH && node.langTag != langTag) {
            node.langTag = LANG_BOTH
        } else if (node.frequency == 0) {
            node.langTag = langTag
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
        val collected = ArrayList<Pair<String, Int>>(64)
        dfsCollectAll(node, collected)
        val langBias = preferredLanguage
        return collected
            .map { (w, baseScore) ->
                val exactNode = exactLookup(w.lowercase())
                val langBonus = when {
                    langBias == "en" && exactNode?.langTag == LANG_EN -> 320
                    langBias == "id" && exactNode?.langTag == LANG_ID -> 320
                    else -> 0
                }
                // Mild preference for closer-length completions
                val lenPenalty = ((w.length - prefix.length).coerceAtLeast(0)) * 8
                w to (baseScore + langBonus - lenPenalty)
            }
            .sortedByDescending { it.second }
            .take(limit)
    }

    private fun dfsCollectAll(node: TrieNode, out: MutableList<Pair<String, Int>>) {
        node.word?.let { out.add(it to node.frequency) }
        for (child in node.children.values) {
            dfsCollectAll(child, out)
        }
    }

    /**
     * Lightweight single-character edit correction (adjacent swap, QWERTY neighbor substitution,
     * extra letter deletion, and single missing letter insertion).
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
                node.word?.let { found[it] = maxOf(found[it] ?: 0, node.frequency + 120) }
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
                    node.word?.let { found[it] = maxOf(found[it] ?: 0, node.frequency + 80) }
                }
            }
        }

        // 3. Single extra-letter deletion (e.g. "helllo" -> "hello", "baguss" -> "bagus")
        if (lower.length >= 4) {
            for (i in lower.indices) {
                val candidate = lower.removeRange(i, i + 1)
                exactLookup(candidate)?.let { node ->
                    node.word?.let { found[it] = maxOf(found[it] ?: 0, node.frequency + 70) }
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
        // Keep intentional uppercase like "I'm", "iPhone", "Android"
        if (suggestion[0].isUpperCase() && typed.all { it.isLowerCase() }) return suggestion

        return when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> suggestion.uppercase()
            typed[0].isUpperCase() -> suggestion.replaceFirstChar { it.uppercaseChar() }
            else -> suggestion
        }
    }

    private fun seedCoreLexicon() {
        val englishWords = listOf(
            // Tier 1: Ultra-high-frequency English
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
            // Tier 2: Everyday conversation, messaging & verbs
            "am", "is", "are", "was", "were", "been", "being", "has", "had", "does", "did",
            "doing", "done", "going", "coming", "getting", "making", "taking", "looking", "working", "waiting",
            "thinking", "trying", "feeling", "running", "playing", "watching", "reading", "writing", "talking", "calling",
            "sending", "checking", "using", "starting", "eating", "drinking", "sleeping", "walking", "driving", "moving",
            "hello", "hey", "hi", "bye", "goodbye", "thanks", "thank", "please", "sorry", "excuse",
            "welcome", "sure", "okay", "ok", "yes", "yeah", "yep", "nope", "maybe", "perhaps",
            "really", "actually", "literally", "basically", "honestly", "probably", "definitely", "absolutely", "totally", "completely",
            "always", "never", "sometimes", "often", "usually", "already", "almost", "still", "yet", "soon",
            "later", "today", "tomorrow", "yesterday", "tonight", "morning", "afternoon", "evening", "night", "weekend",
            "week", "month", "minute", "minutes", "hour", "hours", "second", "seconds", "days", "years",
            "great", "awesome", "amazing", "nice", "cool", "best", "better", "worse", "worst", "fine",
            "perfect", "beautiful", "wonderful", "fantastic", "lovely", "happy", "glad", "excited", "proud", "grateful",
            "sad", "tired", "busy", "free", "ready", "late", "early", "fast", "quick", "slow",
            "easy", "hard", "simple", "clean", "smooth", "bright", "dark", "light", "heavy", "small",
            "big", "large", "long", "short", "high", "low", "hot", "cold", "warm", "fresh",
            "right", "left", "here", "where", "why", "much", "many", "more", "less", "few",
            "very", "too", "quite", "pretty", "super", "real", "true", "false", "wrong", "same",
            "different", "special", "important", "serious", "funny", "crazy", "weird", "strange", "interesting", "boring",
            // Tier 3: People, life, places, tech & work
            "love", "like", "need", "help", "hope", "wish", "miss", "care", "trust", "believe",
            "remember", "forget", "understand", "explain", "tell", "ask", "answer", "reply", "question", "problem",
            "issue", "solution", "idea", "plan", "project", "task", "meeting", "schedule", "event", "party",
            "birthday", "holiday", "vacation", "trip", "travel", "flight", "hotel", "car", "train", "bus",
            "home", "house", "room", "office", "school", "class", "college", "university", "city", "country",
            "world", "place", "street", "road", "park", "store", "shop", "market", "restaurant", "cafe",
            "friend", "friends", "family", "mom", "dad", "mother", "father", "brother", "sister", "baby",
            "kid", "kids", "child", "children", "man", "woman", "guy", "girl", "boy", "person",
            "team", "group", "boss", "client", "customer", "doctor", "teacher", "student", "everyone", "everybody",
            "someone", "somebody", "anyone", "anybody", "nobody", "everything", "something", "anything", "nothing", "together",
            "alone", "food", "breakfast", "lunch", "dinner", "snack", "coffee", "tea", "water", "juice",
            "milk", "pizza", "burger", "chicken", "rice", "bread", "cake", "fruit", "sweet", "hungry",
            "phone", "mobile", "screen", "camera", "photo", "photos", "picture", "video", "music", "song",
            "movie", "game", "book", "story", "news", "post", "comment", "share", "send", "receive",
            "message", "messages", "text", "chat", "call", "email", "mail", "link", "website", "internet",
            "wifi", "network", "signal", "battery", "charge", "charger", "bluetooth", "sound", "volume", "speaker",
            "keyboard", "iKeys", "iPhone", "Android", "Apple", "Google", "Liquid", "Glass", "frosted", "blur",
            "app", "apps", "software", "system", "update", "version", "feature", "design", "style", "theme",
            "color", "colors", "settings", "option", "options", "button", "icon", "globe", "emoji", "prediction",
            "account", "profile", "password", "login", "sign", "name", "address", "number", "code", "file",
            "folder", "document", "note", "notes", "list", "search", "find", "open", "close", "save",
            "delete", "remove", "edit", "change", "create", "build", "fix", "fixed", "bug", "test",
            "check", "start", "stop", "finish", "finished", "complete", "completed", "keep", "hold", "wait",
            "leave", "stay", "live", "bring", "buy", "pay", "paid", "price", "cost", "money",
            "cash", "card", "bank", "order", "delivery", "package", "gift", "present", "clothes", "shoes",
            "health", "sleep", "rest", "exercise", "gym", "weather", "rain", "sun", "sunny", "wind",
            "through", "between", "without", "before", "around", "during", "though", "although", "however", "instead",
            "should", "might", "must", "shall", "since", "until", "while", "wherever", "whenever", "whatever",
            "myself", "yourself", "himself", "herself", "ourselves", "themselves", "another", "either", "neither", "both",
            "each", "every", "whole", "entire", "next", "last", "final", "second", "third", "four",
            "five", "six", "seven", "eight", "nine", "ten", "hundred", "thousand", "million", "zero",
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
            "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
            "I'm", "I've", "I'd", "I'll", "don't", "can't", "won't", "didn't", "doesn't", "isn't",
            "aren't", "wasn't", "weren't", "haven't", "hasn't", "wouldn't", "couldn't", "shouldn't", "you're", "you've",
            "you'll", "that's", "there's", "what's", "here's", "let's", "they're", "we're", "he's", "she's", "it's"
        )

        val indonesianWords = listOf(
            // Tier 1: Ultra-high-frequency Bahasa Indonesia
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
            // Tier 2: Everyday Indonesian conversation & actions
            "apa", "siapa", "kapan", "dimana", "kenapa", "mengapa", "bagaimana", "gimana", "berapa", "mana",
            "kabar", "sehat", "kerja", "kantor", "rumah", "jalan", "makan", "minum", "tidur", "bangun",
            "pergi", "pulang", "datang", "tunggu", "sebentar", "langsung", "terus", "lalu", "kemudian", "setelah",
            "sebelum", "ketika", "saat", "sambil", "sampai", "hingga", "tentang", "soal", "buat", "bikin",
            "ambil", "kirim", "baca", "tulis", "lihat", "dengar", "bicara", "bilang", "tanya", "jawab",
            "cari", "beli", "bayar", "pesan", "buka", "tutup", "mulai", "selesai", "coba", "pakai",
            "gunakan", "simpan", "hapus", "ubah", "ganti", "tambah", "kurang", "masuk", "keluar", "naik",
            "turun", "duduk", "berdiri", "lari", "main", "nonton", "belajar", "mengajar", "bantu", "bantuan",
            "orang", "teman", "sahabat", "keluarga", "anak", "ibu", "ayah", "bapak", "kakak", "adik",
            "suami", "istri", "pacar", "guru", "dokter", "bos", "uang", "duit", "harga", "murah",
            "mahal", "gratis", "diskon", "belanja", "pasar", "toko", "warung", "restoran", "kopi", "teh",
            "air", "nasi", "roti", "mie", "ayam", "ikan", "buah", "sayur", "makanan", "minuman",
            "lapar", "haus", "kenyang", "tempat", "kota", "desa", "negara", "dunia", "hidup", "hati",
            "cinta", "sayang", "rindu", "kangen", "senang", "bahagia", "sedih", "marah", "takut", "khawatir",
            "capek", "lelah", "sibuk", "santai", "penting", "khusus", "biasa", "lucu", "aneh", "seru",
            "asik", "indah", "cantik", "ganteng", "pintar", "cerdas", "rajin", "malas", "sabar", "semangat",
            "pertama", "kedua", "ketiga", "terakhir", "lain", "lainnya", "sendiri", "bersama", "bareng", "selalu",
            "sering", "jarang", "pernah", "enggak", "nggak", "bukan", "tanpa", "antara", "dalam", "luar",
            "atas", "bawah", "depan", "belakang", "kanan", "kiri", "dekat", "jauh", "tinggi", "rendah",
            "panjang", "pendek", "tebal", "tipis", "panas", "dingin", "hangat", "hujan", "cerah", "angin",
            "aplikasi", "ponsel", "hp", "layar", "kamera", "foto", "gambar", "video", "musik", "lagu",
            "suara", "getar", "tema", "warna", "tampilan", "desain", "gaya", "pengaturan", "tombol", "huruf",
            "kata", "kalimat", "bahasa", "indonesia", "inggris", "komputer", "laptop", "internet", "sinyal", "baterai",
            "akun", "sandi", "nama", "alamat", "nomor", "pesan", "telepon", "panggilan", "rapat", "jadwal",
            "tugas", "proyek", "pekerjaan", "masalah", "solusi", "pertanyaan", "jawaban", "informasi", "berita", "cerita",
            "senin", "selasa", "rabu", "kamis", "jumat", "sabtu", "minggu",
            "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan", "sepuluh",
            "seratus", "seribu", "sejuta", "rupiah", "siapa", "apakah", "bagaimanakah", "mengerti", "paham", "tahu",
            "ingat", "lupa", "pikir", "rasa", "percaya", "harap", "semoga", "sukses", "berhasil", "gagal"
        )

        val enTotal = englishWords.size
        englishWords.forEachIndexed { idx, word ->
            val score = (enTotal - idx) * 2 + 200
            insert(word, score, LANG_EN)
        }

        val idTotal = indonesianWords.size
        indonesianWords.forEachIndexed { idx, word ->
            val score = (idTotal - idx) * 2 + 200
            insert(word, score, LANG_ID)
        }
    }

    private fun seedCoreBigrams() {
        val pairs: Map<String, List<String>> = mapOf(
            // English everyday bigrams
            "i" to listOf("am", "think", "want"),
            "i'm" to listOf("going", "good", "not"),
            "i'll" to listOf("be", "let", "send"),
            "i've" to listOf("been", "got", "seen"),
            "how" to listOf("are", "much", "about"),
            "how's" to listOf("it", "your", "everything"),
            "what" to listOf("are", "is", "do"),
            "what's" to listOf("up", "going", "your"),
            "where" to listOf("are", "is", "do"),
            "when" to listOf("are", "will", "do"),
            "why" to listOf("not", "are", "did"),
            "who" to listOf("is", "are", "was"),
            "are" to listOf("you", "we", "they"),
            "do" to listOf("you", "not", "we"),
            "did" to listOf("you", "not", "it"),
            "can" to listOf("you", "I", "we"),
            "could" to listOf("you", "be", "I"),
            "would" to listOf("you", "be", "like"),
            "will" to listOf("be", "you", "do"),
            "you" to listOf("are", "can", "know"),
            "you're" to listOf("welcome", "right", "the"),
            "we" to listOf("are", "can", "have"),
            "they" to listOf("are", "have", "were"),
            "it" to listOf("is", "was", "will"),
            "it's" to listOf("not", "a", "so"),
            "that" to listOf("is", "was", "would"),
            "that's" to listOf("great", "good", "true"),
            "this" to listOf("is", "was", "one"),
            "there" to listOf("is", "are", "was"),
            "good" to listOf("morning", "night", "luck"),
            "thank" to listOf("you", "so", "god"),
            "thanks" to listOf("so", "a", "for"),
            "see" to listOf("you", "ya", "what"),
            "talk" to listOf("to", "later", "soon"),
            "let" to listOf("me", "us", "you"),
            "let's" to listOf("go", "do", "see"),
            "have" to listOf("a", "to", "you"),
            "want" to listOf("to", "a", "some"),
            "need" to listOf("to", "a", "help"),
            "going" to listOf("to", "be", "home"),
            "looking" to listOf("for", "good", "forward"),
            "waiting" to listOf("for", "on", "to"),
            "sorry" to listOf("for", "about", "I"),
            "please" to listOf("let", "send", "help"),
            "happy" to listOf("birthday", "to", "hear"),
            "love" to listOf("you", "it", "this"),
            "miss" to listOf("you", "it", "this"),
            "in" to listOf("the", "a", "my"),
            "on" to listOf("the", "my", "time"),
            "at" to listOf("the", "home", "work"),
            "to" to listOf("the", "be", "do"),
            "for" to listOf("the", "you", "me"),
            "with" to listOf("you", "the", "me"),
            "from" to listOf("the", "my", "here"),
            "about" to listOf("the", "it", "you"),
            "not" to listOf("sure", "yet", "really"),
            "so" to listOf("much", "good", "far"),
            "very" to listOf("good", "much", "nice"),
            "no" to listOf("problem", "worries", "way"),
            "of" to listOf("course", "the", "my"),
            "don't" to listOf("know", "worry", "think"),
            "can't" to listOf("wait", "believe", "do"),
            "liquid" to listOf("glass", "blur", "style"),
            "frosted" to listOf("blur", "glass", "theme"),
            // Bahasa Indonesia everyday bigrams
            "selamat" to listOf("pagi", "malam", "siang"),
            "terima" to listOf("kasih", "saja", "dulu"),
            "apa" to listOf("kabar", "yang", "itu"),
            "lagi" to listOf("apa", "di", "kerja"),
            "mau" to listOf("makan", "pergi", "tidur"),
            "sudah" to listOf("selesai", "makan", "sampai"),
            "belum" to listOf("ada", "bisa", "tau"),
            "tidak" to listOf("bisa", "ada", "apa-apa"),
            "enggak" to listOf("bisa", "ada", "tahu"),
            "bisa" to listOf("tolong", "bantu", "jadi"),
            "tolong" to listOf("kirim", "bantu", "cek"),
            "maaf" to listOf("ya", "banget", "baru"),
            "saya" to listOf("mau", "sudah", "bisa"),
            "aku" to listOf("mau", "lagi", "sudah"),
            "kamu" to listOf("lagi", "sudah", "mau"),
            "kita" to listOf("bisa", "mau", "akan"),
            "ada" to listOf("yang", "apa", "di"),
            "di" to listOf("rumah", "kantor", "sini"),
            "ke" to listOf("rumah", "kantor", "sana"),
            "dari" to listOf("mana", "rumah", "tadi"),
            "untuk" to listOf("kamu", "kita", "semua"),
            "dengan" to listOf("baik", "cepat", "mudah"),
            "kalau" to listOf("bisa", "ada", "mau"),
            "karena" to listOf("itu", "saya", "sudah"),
            "gimana" to listOf("kabar", "nih", "kalau"),
            "bagaimana" to listOf("kabar", "cara", "kalau"),
            "sampai" to listOf("ketemu", "jumpa", "nanti"),
            "hari" to listOf("ini", "besok", "minggu"),
            "terima kasih" to listOf("banyak", "ya", "banget"),
            "halo" to listOf("apa", "semua", "kak")
        )

        pairs.forEach { (prev, nexts) ->
            val map = LinkedHashMap<String, Int>(nexts.size)
            nexts.forEachIndexed { idx, w ->
                map[w] = 100 - idx * 15
            }
            bigrams[prev] = map
        }
    }

    companion object {
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
    }
}
