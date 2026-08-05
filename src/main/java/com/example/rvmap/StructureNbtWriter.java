package com.example.rvmap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;

/**
 * バニラの structure 形式(gzip 圧縮 NBT)を書き出す。
 *
 * <p>キーの構成はバニラの {@code StructureTemplate#writeNbt} に合わせてある:
 * <pre>
 *   size:        TAG_List&lt;TAG_Int&gt; [x, y, z]
 *   palette:     TAG_List&lt;TAG_Compound&gt;  … NbtHelper.fromBlockState() が作る {Name, Properties}
 *   blocks:      TAG_List&lt;TAG_Compound&gt;  … {pos: [x,y,z], state: int}
 *   entities:    TAG_List(空)
 *   DataVersion: TAG_Int … NbtHelper.putDataVersion()
 * </pre>
 * ビット詰めはしない(blocks はブロック 1 個ごとの要素)。
 */
public final class StructureNbtWriter {
	private StructureNbtWriter() {
	}

	public static void write(SchematicBuilder.Result result, Path destination) throws IOException {
		NbtCompound root = new NbtCompound();

		root.put("size", intList(result.sizeX(), result.sizeY(), result.sizeZ()));

		// 実際に使ったブロックだけをパレットに載せる。
		Map<BlockState, Integer> paletteIds = new LinkedHashMap<>();
		NbtList blocks = new NbtList();

		for (SchematicBuilder.Placement placement : result.placements()) {
			Integer existing = paletteIds.get(placement.state());
			int id;

			if (existing == null) {
				id = paletteIds.size();
				paletteIds.put(placement.state(), id);
			} else {
				id = existing;
			}

			NbtCompound block = new NbtCompound();
			block.put("pos", intList(placement.x(), placement.y(), placement.z()));
			block.putInt("state", id);
			blocks.add(block);
		}

		NbtList palette = new NbtList();

		for (BlockState state : paletteIds.keySet()) {
			palette.add(NbtHelper.fromBlockState(state));
		}

		root.put("palette", palette);
		root.put("blocks", blocks);
		root.put("entities", new NbtList());

		NbtHelper.putDataVersion(root);

		NbtIo.writeCompressed(root, destination);
	}

	private static NbtList intList(int... values) {
		NbtList list = new NbtList();

		for (int value : values) {
			list.add(NbtInt.of(value));
		}

		return list;
	}

	/** チャットの集計表示に使う、ブロックステートの文字列表現。 */
	public static String describe(BlockState state) {
		return BlockArgumentParser.stringifyBlockState(state);
	}
}
