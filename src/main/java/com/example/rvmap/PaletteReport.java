package com.example.rvmap;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.Formatting;

/** 自動生成した対応表の 1 行表示。チャットとログで同じ文面を使う。 */
public final class PaletteReport {
	private PaletteReport() {
	}

	public static String line(PaletteConfig.Assignment assignment) {
		if (assignment.failed()) {
			return assignment.blockId() + " -> 解決できません (" + assignment.error() + ")";
		}

		if (!assignment.accepted()) {
			return assignment.blockId() + " -> 色ID " + assignment.colorId()
					+ " (衝突: " + assignment.collidesWith() + " を採用済み。"
					+ PaletteConfig.UNUSED_KEY + " へ退避)";
		}

		return assignment.blockId() + " -> 色ID " + assignment.colorId()
				+ " " + PaletteConfig.describeColor(assignment.colorId());
	}

	public static Formatting colorOf(PaletteConfig.Assignment assignment) {
		if (assignment.failed()) {
			return Formatting.RED;
		}

		return assignment.accepted() ? Formatting.AQUA : Formatting.YELLOW;
	}

	/** ログに出す全行。 */
	public static List<String> logLines(PaletteConfig.GenerationResult generated) {
		List<String> lines = new ArrayList<>();

		for (PaletteConfig.Assignment assignment : generated.assignments()) {
			lines.add(line(assignment));
		}

		if (!generated.missingColorIds().isEmpty()) {
			lines.add("対応ブロックが無い色ID: " + generated.missingColorIds());
		}

		return lines;
	}
}
