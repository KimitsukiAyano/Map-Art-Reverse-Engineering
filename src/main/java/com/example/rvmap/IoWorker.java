package com.example.rvmap;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ファイル I/O 用のワーカースレッド。レンダースレッドを止めないため、
 * 画像のエンコードと NBT の書き出しはここで行う。
 */
public final class IoWorker {
	private static final AtomicInteger COUNTER = new AtomicInteger();

	private static final ThreadFactory FACTORY = runnable -> {
		Thread thread = new Thread(runnable, "rvmap-io-" + COUNTER.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	};

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(FACTORY);

	private IoWorker() {
	}

	public static void submit(Runnable task) {
		EXECUTOR.execute(task);
	}
}
