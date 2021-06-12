# EPGStation の録画を見る
"EPGStationの録画を見る"  はAndroid TV向けに設計されたEPGStation クライアントアプリです。

Webブラウザを持たないAndroid TV から EPGStationの録画を見るために開発されました

## デモ
![](https://raw.githubusercontent.com/wiki/daig0rian/epcltvapp/images/demo.gif)

## Google Play URL
https://play.google.com/store/apps/details?id=com.daigorian.epcltvapp

## 特徴
 - Android TV むけ標準UIのLeanbackテーマを使用
 - Android TVのリモコンだけで操作が完結
 - Android TVのリモコン内蔵マイクから録画済番組を声で検索
 - MX Player やVLCといった外部動画プレーヤーに対応することで高い再生性能を確保

## 必要な環境
 - EPGStation Version 1.x.x or 2.x.x　
 - APIレベル28(Android 9.0)以上の Andorid TV 

## テスト環境
 - SONY BRAVIA KJ-43X8000H (4K液晶androidテレビ)
 - Google Chromecast with Google TV snow　GA01919-JP (HDMIドングル型 Android TV端末) 
 - EPGStatinn Version 1.7.6
 - EPGStation Version 2.3.8

## 他のソリューションとの比較
### Kodi + plugin.video.epgstation
- Google Play Sotreから一発で導入できてらくちん
- 番組を音声検索できる
- ルールごとに録画済一覧を見ることができる。

### Kodi + Harekaze/pvr.epgstation
- EPGStation Version 2.x.x系に対応している

## 今後やりたい
 - 検索履歴を残して再検索を楽にする
 - 動画リストの一番右に「さらに読み込む」ボタンの追加
 - 割と入手性の良い端末であるAmazon FireTVシリーズへの対応、Amazonのストアでの公開

## たぶんできない
 - 録画予約関連UIの実装
   -  ソファーにLeanback（ふんぞり返り）ながらリモコンでやる作業ではないのではないか説
   -  ソファーでスマホからEPGStationの秀逸なUIを操作するのが現実的ではないのではないか説

## 現在の公開状況
 - 2021/06/10 Google Play Sotreでの公開審査完了 (ストア登録から３日くらいかかったかな)
 - 2021/06/11 Andorid TV 端末への配信オプトイン審査完了 (操作から1日未満で完了)
