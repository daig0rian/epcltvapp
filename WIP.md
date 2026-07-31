# WIP: TSシークバーへのサムネイル表示 (Phase 2)

## 目的

Phase 1（[#43](https://github.com/daig0rian/epcltvapp/pull/43)、マージ済み）でTS再生のシーク機能を実装した。そのシーク点の一部にサムネイルを付与し、ユーザーがより意味のある位置へシークできるようにする。

## 前提となる決定事項（Phase 1完了時点の議論より）

- サムネイルは**シーク点全部ではなく10点に1点程度**（`target = max(1, positions.size / 10)`）
- 選定順序は**二分木のBFS走査**（全体の中間点→前半/後半の中間点→…と疎から密に埋める）。中断されても常に「粗いが全体をカバーした状態」を保てる利点がある
- **最大の不確定要素**: 生のARIB放送TSのバイト位置から実際に1枚のフレームをデコードする手段が未検証。候補は`MediaMetadataRetriever`（Android標準、実装は楽だが生ARIB TSを正しく扱えるか不明）。もし使えなければ`tsreadex`経由でMediaCodecを使う自前実装が必要になり、コストが大きく上がる

## Phase 1の設計変更を踏まえた再整理

Phase 1は当初「N点を事前プローブしたテーブル」方式(v2)だったが、起動待ち時間の問題で「head/tailの2点のみ保持し、シーク位置は線形補間で概算、確定時に1回だけ補正プローブ」方式(v3)に変更された（[TsSeekDataProvider.kt](app/src/main/java/com/daigorian/epcltvapp/TsSeekDataProvider.kt)）。サムネイル選定順序(BFS)の設計はv2時代の「事前プローブ済みテーブル」を前提にしていたため、以下の点をv3に合わせて再整理する必要がある:

- `getSeekPositions()`の各点は**時刻のみ**で、バイト位置は`estimateByteOffset()`による概算のみ（未検証）。サムネイル生成には実際のバイト位置が必要なため、選定された点ごとに`TsProbe.refineSeekPoint()`相当の軽量プローブで補正するか、概算のまま使うかは要検討
  - サムネイルは元々「参考程度の見た目」なので概算のままでも実用上問題ない可能性がある。まず`MediaMetadataRetriever`自体が動くかどうかの検証を優先し、精度の話は後回しにする

## 検証結果: MediaMetadataRetrieverは生ARIB TSに使えない（確認済み）

Google TV Streamer実機(`adb logcat`で直接確認)で`investigateMediaMetadataRetriever()`(調査用一時コード)を実行した結果:

```
ATSParser: stream PID 0x140 has invalid stream type 0x0d
ATSParser: stream PID 0x160/0x161/0x162/0x170/0x171/0x172 has invalid stream type 0x0d
ATSParser: Receiving scrambled streams without descrambler!
MPEG2TSExtractor: stopped parsing scrambled content, haveAudio=1, haveVideo=1, elaspedTime=15858
StagefrightMetadataRetriever: all codecs failed to extract frame.
MetadataRetrieverClient: failed to capture a video frame
MediaMetadataRetrieverJNI: getFrameAtTime: videoFrame is a NULL pointer
```

**原因**: Android標準の`MPEG2TSExtractor`/`ATSParser`は、生ARIB TS中のstream_type=0x0d(DSM-CC、ARIBのデータ放送/字幕系PID)のPIDを認識できず、「スクランブルされたストリームだがデスクランブラが無い」と誤判定して解析を中断する。音声・映像自体は検出できている(`haveAudio=1, haveVideo=1`)が、この誤判定によりコーデックにデータが渡らずフレーム取得が失敗する。

これはタイミングや取得位置に依存する問題ではなく、生ARIB TSの構造自体をAndroid標準パーサーが扱えないという構造的な問題。PAT/PMTでこれらのPIDは録画全体に存在するため、どの時刻を狙っても同じ結果になると考えられる（tsreadexが元々この種の非標準ARIB構造を標準デコーダ/プレーヤー向けに正規化するために存在することを踏まえると、想定内の結果）。

**→ MediaMetadataRetrieverをそのまま使う案は不採用。**

## 残タスク

- [ ] **方針決定**: 以下のいずれかで進める
  - (a) tsreadexで正規化した後のバイト列を`MediaMetadataRetriever`に渡す（API 23+の`MediaDataSource`でメモリ上のバイト列を供給、ファイル書き出し不要。minSdk22機種では非対応にするか要検討）
  - (b) 自前でMediaCodecを使い1フレームだけデコードする（実装コストが高い）
- [ ] （方針決定後）サムネイル生成のタイミング・キャッシュ方針の設計
- [ ] BFS選定順序の実装（v3のgetSeekPositions()配列に対して）
- [ ] `TsSeekDataProvider.getThumbnail(index, callback)`の実装（現在は基底クラスのデフォルト実装＝何もしない）
- [ ] UI側の表示確認
- [ ] 調査用一時コード(`investigateMediaMetadataRetriever`)を削除するか、正式な検証コードに置き換える

## 重要な決定事項

- ビルドはAndroid Studioでユーザーが手動実行する（Claude Codeはgradleを叩かない）。`MediaMetadataRetriever`はAndroid実機/エミュレータでしか検証できないため、実機動作確認が必須。
- コルーチン未導入のプロジェクトのため、既存パターン（Handler + バックグラウンドThread）を踏襲する。
