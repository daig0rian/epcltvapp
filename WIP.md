# WIP: Fire TV実機でのMediaCodec初期化フリーズ修正 (Issue #46)

## 結論: PR #45 (サムネイル機能) とその依存だったPR #44 (SDK/media3更新) をrevert

長い調査の末、以下が事実として確定した:

- TSシークバーのサムネイル生成 (PR #45, `androidx.media3.inspector.frame.FrameExtractor`)
  が原因で、Fire TV実機のMediaTek製ハードウェアMPEG2デコーダーが、本編再生用と2つ目の
  インスタンスを同時に開こうとした際にハングする。
- この不具合は端末を再起動するまで解消せず、**アプリ内だけでなく端末上の他アプリ(VLC)の
  動画再生まで巻き込む**、極めて深刻なもの。
- ソフトウェアデコーダーへの切替で回避を試みたが、Fire TV・Google TV Streamerの
  いずれの実機にも、アプリから実際に使えるソフトウェアMPEG2デコーダーは存在しないと
  ログで確認した(コーデックXML上の宣言と、実際に`MediaCodecList`経由で見える候補が
  一致しない)。
- コーデックXMLの`concurrent-instances max="4"`宣言も実態と合っておらず
  (両機種とも4と宣言されているが2インスタンス目で詰まる)、事前に安全性を機械的に
  判定する手段もない。
- 結論として、**本編再生と同時にオンデバイスでデコードしてサムネイルを生成する方式は
  この機種群では成立しない**。media3固有の問題ではなく、MediaCodecを使う実装である限り
  同じ壁にぶつかる。

詳しい調査過程・失敗したアプローチは [CLAUDE.md](CLAUDE.md) の
「検討して撤回した機能」セクションに記録済み。

## 対応内容

- `git revert` で PR #45 (`aa5a19f`)・PR #44 (`6bf6c36`) を順にrevert。
  - `TsThumbnailGenerator.kt` / `app/src/main/res/values/dimens.xml` 削除
  - `TsReadexDataSource.kt` / `TsSeekDataProvider.kt` / `PlaybackVideoFragment.kt` から
    サムネイル関連コードを除去
  - media3 1.10.1 → 1.3.1、compileSdk 36 → 34、minSdk 23 → 22、
    AGP 9.0.1 → 8.7.3、Gradle 9.1.0 → 8.9 に復元
  - `media3-inspector-frame` 依存を削除
- PR #47 (libaribcaption v1.1.2更新) は無関係なため維持。
- CLAUDE.mdに経緯を記録(依存関係バージョン表も連動して復元、新規セクション追加)。

## 完了済み

- [x] 原因調査(非同期MediaCodecアダプタ仮説 → 誤りと判明、revert)
- [x] ログ・実機検証によるサムネイル機能の原因特定
- [x] ソフトウェアデコーダーへの切替を試行 → 両機種とも実用不可と確認
- [x] PR #45 / #44 の `git revert`
- [x] CLAUDE.md 更新(依存関係バージョン表の復元、経緯の記録)

## 残タスク

- [ ] ユーザーによるビルド確認(AGP/Gradle/compileSdkのダウングレードを伴うため、
      Android Studioでのフルビルド・Syncが必要)。
- [ ] Fire TV実機でTS直接再生・サムネイルなしのシークバー操作に問題がないか確認。
- [ ] 動作確認OKならPR作成。

## 重要な決定事項

- minSdk 23への引き上げ(Fire TV 1st/2nd Gen等のAmazon公式サポート終了を理由とする、
  サムネイルとは無関係の独立した根拠があった)は、今回は#44ごと丸ごとrevertした。
  再度minSdkを上げたい場合は、別途その根拠単独で新しいPRを立てること。
- 本ブランチ名は`fix/firetv-mediacodec-hang`のまま維持(方針が変わっても、issue #46
  そのものへの対応という点は一貫しているため)。
