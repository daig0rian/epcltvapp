# WIP: libaribcaption v1.1.2 への更新

## 目的

libaribcaption の作者 (xqq 氏) から X 経由で「Android で字幕表示位置が本来より上にずれるバグがあった、最新版を使ってほしい」との連絡を受けた。該当修正を含む最新版 (v1.1.2) へサブモジュールを更新する。

## 経緯・調査結果

- 現在のピン: `f9d8c50f` (2026-05-01, v1.0.0 から43コミット後)
- 更新先: `c64c23b8` (v1.1.2 タグ, 2026-07-25)
- 該当バグ修正: **PR #20 "renderer/freetype: improve CJK baseline placement"**
  (`b27fdb5e9`) — 従来はフォント全体の ascender/descender メトリクスで縦位置を
  決めていたが、これは実際の CJK グリフの視覚的な境界を必ずしも反映しない
  ため位置がずれていた。代表的な漢字・かなグリフの実測バウンディングボックス
  から Ideographic Character Face のベースラインを推定する方式に変更。
  → 報告された「字幕が本来より上に表示される」症状と一致。
- 同 PR 内の関連修正: `85ff2ec02` — フォールバックフォント切り替え時に
  半角置換(GSUB)キャッシュが無効化されず古いマップが使われるバグの修正。
- **PR #19 "Fix invalid FontFamily reference in Android font parser"**
  (`9a7abc1e2`) — Android の `fonts.xml` パース時、`fallbackFor` 属性処理中に
  vector が再配置されて参照が無効化され未定義動作を起こす可能性があったバグの
  修正。Android 専用コードパスに直接影響。
- その他: フォント参照バグ修正の際に埋め込み FreeType を 2.11.1 → 2.14.3 に
  アップグレード。Fontconfig 向け修正 (`c8cd4e21b`) は Linux 専用で
  Android ビルドには無関係。
- public ヘッダ (`include/aribcaption/`) に差分なし → ABI/API 互換、
  JNI 側 (`aribcaption_jni.cpp`) の変更は不要と確認済み。

## 重要な決定事項: CMake バージョンの引き上げが必須

libaribcaption 側の `CMakeLists.txt` が `cmake_minimum_required` を
**3.11 → 3.28** に引き上げた(埋め込み FreeType の取り込みに
`FetchContent_MakeAvailable()` + `EXCLUDE_FROM_ALL` を使うようになったため。
これは CMake 3.28 で追加された機能)。

このため、単純にサブモジュールのピンを上げるだけではビルドが失敗する
(`app/build.gradle` で `version "3.22.1"` を明示指定しており、ローカル
SDK にも 3.22.1 しか入っていないため)。

対応として以下を実施:
- `app/build.gradle` の `externalNativeBuild.cmake.version` を
  `3.22.1` → `3.30.5` に変更。
  - 当初 `3.28.1` を指定したが、Android Studio の SDK Manager (SDK Tools タブ)
    に表示される実際のパッケージ一覧には `3.28.1`/`3.27.x` が存在せず、
    ビルド時に `CXX1300`/`CXX1301` (CMake 3.28.1 が見つからない) で失敗した。
    一覧に存在するバージョンの中から要件 (3.28 以上) を満たす `3.30.5` を
    採用。
- `app/src/main/cpp/CMakeLists.txt` の `cmake_minimum_required` も
  `3.22` → `3.28` に変更(実態に合わせる。3.30.5 が要求する最低要件ではなく
  libaribcaption 側の要件に合わせて 3.28 のままでよい)。

**注意:** SDK Manager で表示可能な CMake バージョンは Google 側の配布状況で
変動する。事前に `dl.google.com/android/repository/repository2-3.xml` を
WebFetch で確認したが、そのレスポンスは実際に配布されている最新バージョン群
(3.30.x, 3.31.x, 4.0.x, 4.1.0 など)を反映しておらず古い/不完全な情報だった。
以後この手のバージョン確認は SDK Manager の実際の一覧(スクリーンショット等)
で行うほうが確実。

## 完了済み

- [x] libaribcaption リポジトリのコミット履歴調査 (f9d8c50f → v1.1.2 の全差分)
- [x] 該当するバグ修正の特定 (baseline placement 修正)
- [x] public API 差分なしの確認
- [x] サブモジュールを v1.1.2 (`c64c23b8`) に更新
- [x] `app/build.gradle` の cmake バージョンを 3.28.1 に更新
- [x] `app/src/main/cpp/CMakeLists.txt` の cmake_minimum_required を 3.28 に更新

## 残タスク

- [ ] ユーザーによる Android Studio でのビルド確認
  - CMake 3.28.1 が未インストールの場合、SDK Manager (SDK Tools タブ) で
    インストールが必要になる可能性がある(Gradle sync 時に自動プロンプトが
    出ることが多い)。
- [ ] 実機/エミュレータでの字幕表示位置の確認(TS録画・H.264 双方、特に
    CJK 文字を含む字幕で、修正前より縦位置が適切かを目視確認)
- [ ] 動作確認後、WIP.md を削除してコミット → PR 作成
