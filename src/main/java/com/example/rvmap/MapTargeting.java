package com.example.rvmap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * 視線の先にある額縁と、その中の地図を解決する。
 *
 * <p>{@code MinecraftClient.crosshairTarget} はバニラのリーチ距離(3〜6 ブロック)までしか
 * 見ていないので、壁一面のマップアートには届かない。ここでは視点位置と向きから
 * 自前でエンティティレイキャストを行う。
 */
public final class MapTargeting {
	private MapTargeting() {
	}

	/** 解決結果。ここまで来た時点で {@code state} は非 null。 */
	public record Target(ItemFrameEntity frame, MapIdComponent mapId, MapState state) {
	}

	/** 失敗理由。チャットのメッセージを呼び出し側で出し分けるために使う。 */
	public enum Failure {
		NO_WORLD,
		NO_FRAME,
		NOT_A_MAP,
		NO_MAP_STATE
	}

	public sealed interface Result permits Success, Error {
	}

	public record Success(Target target) implements Result {
	}

	public record Error(Failure failure) implements Result {
	}

	public static Result resolve(MinecraftClient client) {
		ClientWorld world = client.world;
		Entity camera = client.getCameraEntity();

		if (world == null || camera == null) {
			return new Error(Failure.NO_WORLD);
		}

		ItemFrameEntity frame = raycastItemFrame(client, camera, Rvmap.REACH);

		if (frame == null) {
			return new Error(Failure.NO_FRAME);
		}

		ItemStack held = frame.getHeldItemStack();
		// ItemFrameEntity#getMapId は中身が地図でなければ null を返す
		// (実装は stack.get(DataComponentTypes.MAP_ID))。
		MapIdComponent mapId = frame.getMapId(held);

		if (mapId == null) {
			return new Error(Failure.NOT_A_MAP);
		}

		MapState state = world.getMapState(mapId);

		if (state == null) {
			return new Error(Failure.NO_MAP_STATE);
		}

		return new Success(new Target(frame, mapId, state));
	}

	/**
	 * 視点から {@code maxDistance} まで飛ばして、最初に当たった額縁を返す。
	 *
	 * <p>ブロックのレイキャストも併せて行い、壁の向こうにある額縁を拾わないようにしている。
	 */
	@Nullable
	public static ItemFrameEntity raycastItemFrame(MinecraftClient client, Entity camera, double maxDistance) {
		Vec3d start = camera.getCameraPosVec(1.0F);
		Vec3d direction = camera.getRotationVec(1.0F);
		Vec3d end = start.add(direction.multiply(maxDistance));

		Box searchBox = camera.getBoundingBox()
				.stretch(direction.multiply(maxDistance))
				.expand(1.0D);

		EntityHitResult entityHit = ProjectileUtil.raycast(
				camera, start, end, searchBox,
				entity -> entity instanceof ItemFrameEntity && !entity.isSpectator(),
				maxDistance * maxDistance);

		if (entityHit == null) {
			return null;
		}

		// 視線を遮るブロックがあれば、そこより手前の額縁だけを採用する。
		BlockHitResult blockHit = camera.getWorld().raycast(new RaycastContext(
				start, end,
				RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE,
				camera));

		if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK) {
			// 額縁は壁に貼り付いているので、ほぼ同じ距離になることがある。少し余裕を持たせる。
			double blockDistance = start.squaredDistanceTo(blockHit.getPos());
			double frameDistance = start.squaredDistanceTo(entityHit.getPos());

			if (frameDistance > blockDistance + 1.0D) {
				return null;
			}
		}

		return (ItemFrameEntity) entityHit.getEntity();
	}

	public static String failureMessage(Failure failure) {
		return switch (failure) {
			case NO_WORLD -> "ワールドに入っていないため実行できません。";
			case NO_FRAME -> "視線の先 " + (int) Rvmap.REACH + " ブロック以内に額縁が見つかりません。地図の中心あたりを狙ってください。";
			case NOT_A_MAP -> "額縁の中身が地図ではありません。";
			case NO_MAP_STATE -> "地図データがまだクライアントに届いていません。数秒間その地図を見続けてから再実行してください。";
		};
	}
}
