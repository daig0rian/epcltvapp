# WIP: Fire TV実機でのMediaCodec初期化フリーズ修正 (Issue #46)

## 目的

Issue #46: Fire TV実機（Fire OS API 28相当・AFTSSS）で録画済みオリジナルTSを直接再生すると、
`OMXClient: IOmx service obtained` 直後でMediaCodec初期化がフリーズし応答不能になる。

## 原因

media3 (`androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory`) は、
SDK 28〜30 かつ `com.amazon.hardware.tv_screen` (Amazon Fire TV) を検出した場合、
パフォーマンス改善目的で本来非対象のこのSDK帯でも非同期MediaCodecアダプタ
(`AsynchronousMediaCodecAdapter`, HandlerThreadコールバック経由) を特別に有効化する。
この経路がFire TV実機でMediaCodec configure/start直後にハングする。

tsreadexネイティブ処理のON/OFFに関わらず同じ地点で再現することがissue内の調査で
確認済みのため、原因はTS処理側ではなくExoPlayer/MediaCodec初期化経路にあると判断。

## 対応

`PlaybackVideoFragment.kt` の ExoPlayer構築時、上記の条件
(SDK 28〜30 かつ `com.amazon.hardware.tv_screen`) に一致する場合のみ
`DefaultRenderersFactory.forceDisableMediaCodecAsynchronousQueueing()` で
同期MediaCodecアダプタに強制フォールバックする。

SDK 31以上はupstream (media3) が「全機種で非同期が安定している」と明言しているため
対象外とし、この特定条件のみに絞ったピンポイントの修正とした。

## 完了済み

- [x] 原因特定 (media3ソース `DefaultMediaCodecAdapterFactory.shouldUseAsynchronousAdapterInDefaultMode()` を確認)
- [x] `PlaybackVideoFragment.kt` に条件分岐と `forceDisableMediaCodecAsynchronousQueueing()` を追加

## 残タスク

- [ ] ユーザーによるビルド・実機動作確認 (Fire TV実機でのTS直接再生フリーズ解消の確認が必須)
- [ ] 動作確認OKならコミット → PR作成

## 重要な決定事項

- 修正範囲はSDK 28〜30 + Amazon Fire TV機種検出時のみに限定。全機種で非同期を無効化すると
  ExoPlayer側が意図したFire TV向けパフォーマンス改善を無関係な機種/SDKでも失うため。
- HLS経由再生や他番組での再現性はissue内で未確認事項として残っているが、原因箇所が
  ExoPlayer初期化の入口(MediaCodecアダプタ選択)であるため、コンテンツ種別に関わらず
  同一の原因である可能性が高いと判断し、そちらの追加調査は行わず本修正を先に適用する。
