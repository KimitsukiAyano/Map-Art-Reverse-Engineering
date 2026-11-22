package com.example.simpleautofish.test;

import java.util.ArrayList;
import java.util.List;

/** Names of serverbound packets sent by the client, in order (test only). */
public final class PacketLog {
	private static final List<String> PACKETS = new ArrayList<>();
	private static boolean recording;

	public static synchronized void record(Object packet) {
		if (recording) PACKETS.add(packet.getClass().getSimpleName());
	}

	public static synchronized void start() {
		PACKETS.clear();
		recording = true;
	}

	/** Stops recording and returns the packets of the client tick that contained the first UseItem packet. */
	public static synchronized List<String> stopAndGetUseTick() {
		recording = false;
		int use = PACKETS.indexOf("ServerboundUseItemPacket");
		if (use < 0) return List.of();
		int start = PACKETS.subList(0, use).lastIndexOf("ServerboundClientTickEndPacket") + 1;
		int end = PACKETS.subList(use, PACKETS.size()).indexOf("ServerboundClientTickEndPacket");
		return List.copyOf(PACKETS.subList(start, end < 0 ? PACKETS.size() : use + end + 1));
	}
}
