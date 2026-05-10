package com.daigorian.epcltvapp

object AribGenre {
    fun getGenreText(genre: Long?, subGenre: Long?): String {
        if (genre == null) return ""
        val main = mainNames[genre.toInt()] ?: return ""
        val sub = subGenre?.let { subNames[genre.toInt()]?.get(it.toInt())?.takeIf { s -> s.isNotEmpty() } }
        return if (sub != null) "$main / $sub" else main
    }

    private val mainNames = mapOf(
        0 to "ニュース・報道",
        1 to "スポーツ",
        2 to "情報・ワイドショー",
        3 to "ドラマ",
        4 to "音楽",
        5 to "バラエティ",
        6 to "映画",
        7 to "アニメ・特撮",
        8 to "ドキュメンタリー・教養",
        9 to "劇場・公演",
        10 to "趣味・教育",
        11 to "福祉",
        15 to "その他"
    )

    private val subNames = mapOf(
        0 to mapOf(
            0 to "定時・総合", 1 to "天気", 2 to "特集・ドキュメント", 3 to "政治・国会",
            4 to "経済・市況", 5 to "海外・国際", 6 to "解説", 7 to "討論・会談",
            8 to "報道特番", 9 to "ローカル・地域", 10 to "交通", 15 to "その他"),
        1 to mapOf(
            0 to "スポーツニュース", 1 to "野球", 2 to "サッカー", 3 to "ゴルフ",
            4 to "その他の球技", 5 to "相撲・格闘技", 6 to "オリンピック・国際大会",
            7 to "マラソン・陸上・水泳", 8 to "モータースポーツ",
            9 to "マリン・ウィンタースポーツ", 10 to "競馬・公営競技", 15 to "その他"),
        2 to mapOf(
            0 to "芸能・ワイドショー", 1 to "ファッション", 2 to "暮らし・住まい",
            3 to "健康・医療", 4 to "ショッピング・通販", 5 to "グルメ・料理",
            6 to "イベント", 7 to "番組紹介・お知らせ", 15 to "その他"),
        3 to mapOf(0 to "国内ドラマ", 1 to "海外ドラマ", 2 to "時代劇", 15 to "その他"),
        4 to mapOf(
            0 to "国内ロック・ポップス", 1 to "海外ロック・ポップス", 2 to "クラシック・オペラ",
            3 to "ジャズ・フュージョン", 4 to "歌謡曲・演歌", 5 to "ライブ・コンサート",
            6 to "ランキング・リクエスト", 7 to "カラオケ・のど自慢", 8 to "民謡・邦楽",
            9 to "童謡・キッズ", 10 to "民族音楽・ワールドミュージック", 15 to "その他"),
        5 to mapOf(
            0 to "クイズ", 1 to "ゲーム", 2 to "トークバラエティ", 3 to "お笑い・コメディ",
            4 to "音楽バラエティ", 5 to "旅バラエティ", 6 to "料理バラエティ", 15 to "その他"),
        6 to mapOf(0 to "洋画", 1 to "邦画", 2 to "アニメ", 15 to "その他"),
        7 to mapOf(0 to "国内アニメ", 1 to "海外アニメ", 2 to "特撮", 15 to "その他"),
        8 to mapOf(
            0 to "社会・時事", 1 to "歴史・紀行", 2 to "自然・動物・環境",
            3 to "宇宙・科学・医学", 4 to "カルチャー・伝統文化", 5 to "文学・文芸",
            6 to "スポーツ", 7 to "ドキュメンタリー全般", 8 to "インタビュー・討論", 15 to "その他"),
        9 to mapOf(
            0 to "現代劇・新劇", 1 to "ミュージカル", 2 to "ダンス・バレエ",
            3 to "落語・演芸", 4 to "歌舞伎・古典", 15 to "その他"),
        10 to mapOf(
            0 to "旅・釣り・アウトドア", 1 to "園芸・ペット・手芸", 2 to "音楽・美術・工芸",
            3 to "囲碁・将棋", 4 to "麻雀・パチンコ", 5 to "車・オートバイ",
            6 to "コンピュータ・ＴＶゲーム", 7 to "会話・語学", 8 to "幼児・小学生",
            9 to "中学生・高校生", 10 to "大学生・受験", 11 to "生涯教育・資格",
            12 to "教育問題", 15 to "その他"),
        11 to mapOf(
            0 to "高齢者", 1 to "障害者", 2 to "社会福祉", 3 to "ボランティア",
            4 to "手話", 5 to "文字(字幕)", 6 to "音声解説", 15 to "その他"),
        15 to mapOf(15 to "その他")
    )
}
