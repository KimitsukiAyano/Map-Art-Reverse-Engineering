package com.example.oresim;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A minimal {@link WorldGenLevel} built as a dynamic proxy.
 *
 * Vanilla placement modifiers (count, in_square, height_range, rarity, biome filter)
 * only ask the level for its height bounds, the seed, biomes, heightmaps and block
 * states. Everything else is unsupported and throws, so a future modifier that needs
 * more is reported instead of silently producing wrong positions.
 */
final class SimLevel {
	private SimLevel() {
	}

	static WorldGenLevel create(
		long seed,
		int minY,
		int height,
		int seaLevel,
		Function<BlockPos, Holder<Biome>> biomes,
		Function<BlockPos, BlockState> blocks,
		ToIntBiFunction<Integer, Integer> surfaceHeight
	) {
		InvocationHandler handler = (proxy, method, args) -> {
			String name = method.getName();
			int argc = args == null ? 0 : args.length;

			switch (name) {
				case "getSeed":
					return seed;
				case "getMinY":
					if (argc == 0) return minY;
					break;
				case "getHeight":
					if (argc == 0) return height;
					if (argc == 3) return surfaceHeight.applyAsInt((Integer) args[1], (Integer) args[2]);
					break;
				case "getSeaLevel":
					return seaLevel;
				case "getBiome":
					if (argc == 1 && args[0] instanceof BlockPos pos) return biomes.apply(pos);
					break;
				case "getBlockState":
					if (argc == 1 && args[0] instanceof BlockPos pos) return blocks.apply(pos);
					break;
				case "ensureCanWrite":
					return true;
				case "setCurrentlyGenerating":
					return null;
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				case "toString":
					return "OreSimLevel";
				default:
					break;
			}

			if (method.isDefault()) {
				return InvocationHandler.invokeDefault(proxy, method, args);
			}

			throw new UnsupportedOperationException("OreSim: level method not simulated: " + describe(method));
		};

		return (WorldGenLevel) Proxy.newProxyInstance(SimLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, handler);
	}

	private static String describe(Method method) {
		return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
	}
}
