# rvmap

額縁に飾られたマップアートを見つめてコマンドを打つと、その地図を

- **128x128 の PNG**(`/rvmap copy`)
- **バニラ structure 形式の .nbt**(`/rvmap build`)

としてローカルに保存する、Minecraft 1.21.1 用の **クライアント専用** Fabric MOD です。

サーバーは地図を描画するために `MapUpdateS2CPacket` でカラーデータをクライアントへ
送っています。rvmap はそれをクライアント側の `MapState` から読み出して画像や
スキーマティックに変換します。サーバーへ何かを送ることはありません。

---

## 必要環境

| 項目 | バージョン |
| --- | --- |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16.14 以上 |
| Fabric API | 0.116.15+1.21.1 で確認 |
| Java | 21 |

クライアント専用です(`fabric.mod.json` の `environment` は `"client"`)。
サーバーに入れる必要はありませんし、入れても動きません。

---

## ビルド方法

```bash
git clone <このリポジトリ>
cd Map-Art-Reverse-Engineering
./gradlew build
```

`build/libs/rvmap-1.0.0.jar` が生成されます(`-sources.jar` は入れなくて構いません)。
これを `.minecraft/mods/` に Fabric API と一緒に置いてください。

---

## 使い方

### `/rvmap copy [ファイル名]`

視線の先の額縁に入っている地図を、128x128 の PNG として保存します。

- ファイル名を省略すると `map_<mapId>_<yyyyMMdd_HHmmss>.png` になります。
- 未探索(基本色ID 0)のピクセルは **アルファ 0**(完全な透明)で書き出されます。
- 色は Minecraft 内部の `MapColor.getRenderColor(パック値)` から取っています。
  自前の色テーブルは持っていません。

### `/rvmap build [ファイル名]`

視線の先の地図から、**バニラ structure 形式(gzip 圧縮 NBT)** の `.nbt` を生成します。
Litematica にそのまま読み込めることを目標にしています。

- ファイル名を省略すると `map_<mapId>_<yyyyMMdd_HHmmss>.nbt` になります。
- 生成後、チャットに次の情報が出ます。
  - 領域サイズ
  - 使用ブロックの種類と個数(建材の必要量としてそのまま使えます)
  - 最大高さと、最も高い列の x 座標
  - パレット未定義だった基本色IDの一覧(あれば)

### 額縁の狙い方

`MinecraftClient.crosshairTarget` はバニラのリーチ距離までしか見ていないので、
壁一面のマップアートには届きません。rvmap は視点位置と向きから自前で
エンティティレイキャストを行い、**32 ブロック**まで額縁を探します。
視線を遮るブロックがあれば、その手前の額縁だけを拾います。

---

## 保存先

すべて **起動構成のディレクトリ**(`MinecraftClient.getInstance().runDirectory`)直下の
`rvmap/` フォルダに保存されます。

```
<インスタンスのディレクトリ>/
└── rvmap/
    ├── palette.json
    ├── map_1234_20260805_120000.png
    └── map_1234_20260805_120500.nbt
```

通常は `.minecraft/rvmap/` ですが、MultiMC / Prism などでインスタンスごとに
ディレクトリが分かれている場合はそのインスタンスの中に作られます。
フォルダが無ければ自動で作成します。

保存に成功すると、チャットに相対パスが緑字で出ます。**クリックするとフォルダが開きます。**

入力したファイル名にパス区切り文字や `..` が含まれていた場合はサニタイズされ、
必ず `rvmap/` 直下の 1 ファイルになります(日本語のファイル名は使えます)。

---

## `rvmap/palette.json`

`/rvmap build` が使う「基本色ID → ブロックステート文字列」の対応表です。
**初回起動時に自動生成されます。**

```json
{
  "_comment": "基本色ID -> ブロックステート文字列。/rvmap build のときに実際のレジストリと照合されます。",
  "noob_line": "minecraft:stone",
  "1": "minecraft:grass_block",
  "2": "minecraft:sandstone",
  "13": "minecraft:oak_planks"
}
```

- キーが数字のものが基本色IDの対応です(1〜63)。
- `noob_line` は最北端に足す 1 行(ヌーブライン)のブロック。既定は石です。
- `_` で始まるキーはコメント扱いで無視されます。
- 値には `minecraft:oak_leaves[persistent=true]` のようにブロックステートも書けます。

### 検証

**起動時と `/rvmap build` 実行時の両方で必ず検証します。**
各エントリを実際にパースし、その `BlockState` の MapColor の ID がキーの基本色IDと
一致するかを**ゲーム内のレジストリで照合**します。

不一致やパース失敗があると、チャットに赤字で

```
色ID 13 に指定された minecraft:sand は実際には色ID 2 です (#7FB238 ではなく #F7E9A3)。
```

のように具体的に報告し、**生成を中止します**。

起動時に見つかった問題は、ワールドに参加した直後にチャットへまとめて流れます。

---

## 変換アルゴリズム(`/rvmap build`)

1. `colors` の各バイトを `基本色ID = v >> 2`、`明度 = v & 3` に分解する。
2. **明度 → 高さ**(縮尺 1:1 前提)。北端から南へ、x 列ごとに独立して累積する。
   - 明度 2 → 北隣 +1
   - 明度 1 → 北隣と同じ
   - 明度 0 → 北隣 −1
   - **明度 3 が出現したら中止して警告**(水深表現。この地図は非対応)
3. 北端に**ヌーブライン 1 行**を追加する。したがって領域サイズは
   X=128、Z=**129**(z=0 がヌーブライン、z=1..128 が画像)。
4. 各 x 列を独立に平行移動し、その列の最低高さが y=0 になるよう正規化する。
   明度は南北の**相対差**しか表さないので、列ごとの平行移動は像を変えません。
5. 基本色ID 0(未探索/透明)のピクセルは air にし、出現した場合は
   「列の連続性が途切れます」と警告する。

色のRGB変換・最近傍探索・ディザリングは**一切行いません**。
基本色IDは既に確定値であり、推定処理を挟むとかえって劣化するためです。

---

## 生成される .nbt について

バニラの `StructureTemplate` と同じスキーマです。

| キー | 型 | 内容 |
| --- | --- | --- |
| `size` | `TAG_List<TAG_Int>` | `[128, 高さ, 129]` |
| `palette` | `TAG_List<TAG_Compound>` | 実際に使ったブロックのみ(`{Name, Properties}`) |
| `blocks` | `TAG_List<TAG_Compound>` | `{pos: [x,y,z], state: int}` をブロック 1 個につき 1 要素 |
| `entities` | `TAG_List` | 空 |
| `DataVersion` | `TAG_Int` | 実行中のゲームのもの(1.21.1 では 3955) |

ビット詰めはしていません。ファイルは gzip 圧縮されています。

### 読み込ませ方

- **Litematica**: `Load Schematic` から `rvmap/` を開いて `.nbt` を選ぶ。
- **バニラのストラクチャーブロック**: `<セーブデータ>/generated/minecraft/structures/` に
  置いてからストラクチャーブロックの Load で名前を指定する。

`.litematic` 形式は**まだ作りません**。独自のビット詰めが必要でリスクが高いため、
vanilla `.nbt` が Litematica で正しく読めることを確認してから別途相談する方針です。

---

## 既知の制限

- **明度 3 の地図は非対応。** 水深の表現に使われるため、階段状のマップアートには
  変換できません。検出したら中止します。
- **縮尺 1:1(scale 0)専用。** それ以外の縮尺の地図でも生成はしますが、
  高さの計算が前提から外れるので警告を出します。
- **基本色ID 7(濃い緑)と 12(水色)** は、フルブロックの不透明ブロックが
  バニラに存在しません。既定ではそれぞれ `minecraft:oak_leaves[persistent=true]` と
  `minecraft:water` を割り当てていますが、水は階段状の造形には向きません。
  必要に応じて `palette.json` を書き換えてください。
- **未探索ピクセルがあると列が途切れます。** 基本色ID 0 は air になるため、
  そこから南のブロックが宙に浮きます。地図を完全に描画してから使ってください。
- **地図データが届いていないと失敗します。** ワールドに入った直後や遠くから見ただけの
  地図は `MapState` が `null` です。数秒間その地図を見続けてから再実行してください。
- 額縁が **32 ブロック**より遠い、または視線がブロックで遮られている場合は見つかりません。
- 地図の**装飾(バナーマーカーやプレイヤーアイコン)は書き出しません**。
  `colors` に入っているピクセルデータのみを扱います。

---

## 実装メモ

Mixin は使っていません。すべて Fabric API の公開イベントと Minecraft の公開 API だけで
実装しています。

ファイル I/O(PNG エンコードと NBT の書き出し)は `rvmap-io` という
デーモンスレッド上で行い、レンダースレッドを止めないようにしています。
`MapState.colors` はサーバーからのパケットで書き換わるため、ゲームスレッドのうちに
複製してからワーカーに渡しています。

### 色のバイト順

`MapColor#getRenderColor(Brightness)` は 1.21.1 では

```java
return 0xFF000000 | (b << 16) | (g << 8) | r;
```

を返します。つまり戻り値は **ABGR**(最下位バイトが赤)で、`NativeImage` が期待する並びです。
`BufferedImage.TYPE_INT_ARGB` は ARGB なので、赤と青を入れ替える必要があります。
マッピング済み jar 上で実際に確認した値:

```
id= 4 (BRIGHT_RED)  color=0xFF0000  getRenderColor(18) = 0xFF0000FF
id=12 (WATER_BLUE)  color=0x4040FF  getRenderColor(50) = 0xFFFF4040
```

書き出した PNG も目視で確認済みです(赤は赤、青は青になります)。

### 参照している API(1.21.1 / yarn 1.21.1+build.3)

うろ覚えで書かず、マッピング済みの jar から実際のシグネチャを確認しています。

| 用途 | API |
| --- | --- |
| 額縁からマップID | `ItemFrameEntity#getMapId(ItemStack)` → `MapIdComponent`(中身が `stack.get(DataComponentTypes.MAP_ID)`) |
| ワールドから MapState | `World#getMapState(MapIdComponent)` |
| パック値 → 色 | `MapColor#getRenderColor(int)`(戻り値 ABGR) |
| BlockState の色ID | `BlockState#getMapColor(BlockView, BlockPos)` → `MapColor#id` |
| ブロックステート文字列のパース | `BlockArgumentParser#block(RegistryWrapper<Block>, String, boolean)` |
| structure のパレット要素 | `NbtHelper#fromBlockState(BlockState)` |
| DataVersion | `NbtHelper#putDataVersion(NbtCompound)` |
| エンティティレイキャスト | `ProjectileUtil#raycast(Entity, Vec3d, Vec3d, Box, Predicate, double)` |

---

## 開発用の検証ツール

`src/tools/java` に、ゲームを起動せずに検証するためのオフラインツールが入っています。
通常の `./gradlew build` には一切含まれません(`-PrvmapTools` を付けたときだけ有効)。

yarn でマッピングされた Minecraft の jar は、難読化時に同じパッケージだったクラスが
別パッケージに分かれるため、Fabric のランタイム外で動かすと `IllegalAccessError` が出ます。
そのため、アクセス修飾子を public に開いたコピーを一度だけ作ってから実行します。

アクセス修飾子を開いたコピーは `publicizeMinecraft` タスクが自動で作るので、
下のコマンドを打つだけです。

```bash
# 色の並びを確認し、全 MapColor のスウォッチ PNG と
# 「ブロック -> MapColor ID」の一覧を build/probe/ に出す
./gradlew colorProbe -PrvmapTools

# パレット検証 / 高さ計算 / NBT 出力 / ファイル名サニタイズのスモークテスト
./gradlew schematicSmokeTest -PrvmapTools
```

`schematicSmokeTest` は、生成した `.nbt` をバニラの `StructureTemplate#readNbt` に
そのまま読み戻すところまで確認します(Litematica も同じスキーマを読みます)。

既定の `palette.json` の 61 エントリは、この方法で 1.21.1 のブロックレジストリを
実際にダンプして、すべて MapColor の ID が一致することを確認済みです。

---

## ライセンス

MIT
