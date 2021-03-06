package net.cheney.reactor.threadpool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

import net.cheney.reactor.AsyncSocketChannel;
import net.cheney.reactor.ClientProtocolFactory;
import net.cheney.reactor.CompletionHandler;
import net.cheney.reactor.EventHandler;
import net.cheney.reactor.Reactor;

public final class ThreadPoolAsyncSocketChannel extends AsyncSocketChannel {

	private final Queue<EventHandler<SocketChannel>> pendingReads = new ConcurrentLinkedQueue<EventHandler<SocketChannel>>();
	private final Queue<EventHandler<SocketChannel>> pendingWrites = new ConcurrentLinkedQueue<EventHandler<SocketChannel>>();
	private final ExecutorService executor;

	protected ThreadPoolAsyncSocketChannel(final Reactor reactor, final ClientProtocolFactory factory, final ExecutorService executor) throws IOException {
		super(reactor, SocketChannel.open(), factory, SelectionKey.OP_CONNECT);
		this.executor = executor;
	}

	protected ThreadPoolAsyncSocketChannel(final Reactor reactor, final SocketChannel sc, final ExecutorService executor) throws IOException {
		super(reactor, sc, null, 0);
		this.executor = executor;
	}

	@Override
	public final void doRead() throws IOException {
		for (EventHandler<SocketChannel> handler = pendingReads.peek(); handler != null; handler = pendingReads.peek()) {
			if (handler.handleEvent(channel())) {
				pendingReads.remove(handler);
				handler.complete();
			} else {
				return;
			}
		}
		disableReadInterest();
	}

	@Override
	public final void read(final ByteBuffer buff, final CompletionHandler<ByteBuffer> handler) {
		final class ReadEventHandler implements EventHandler<SocketChannel> {
			
			final class ReadEventCompletedHandler implements Runnable {

				public final void run() {
					handler.completed(buff);
				}
				
			}
			
			private final ReadEventCompletedHandler readEventCompletedHandler = new ReadEventCompletedHandler();
			
			public final boolean handleEvent(final SocketChannel channel) throws IOException {
				switch (channel.read(buff)) {
				case -1:
					close();
				case 0:
					return false;

				default:
					return true;
				}
			}
			
			@Override
			public void complete() {
				executor.execute(readEventCompletedHandler);
			}
		}
		
		read(new ReadEventHandler());
	}
	
	@Override
	public final void read(final EventHandler<SocketChannel> eventHandler) {
		pendingReads.add(eventHandler);
		enableReadInterest();
	}

	@Override
	public final void doWrite() throws IOException {
		for (EventHandler<SocketChannel> handler = pendingWrites.peek(); handler != null; handler = pendingWrites.peek()) {
			if (handler.handleEvent(channel())) {
				pendingWrites.remove(handler);
				handler.complete();
			} else {
				return;
			}
		}
		disableWriteInterest();
	}

	@Override
	public final void write(final ByteBuffer buff, final CompletionHandler<ByteBuffer> handler) {
		final class WriteEventHandler implements EventHandler<SocketChannel> {
			
			final class WriteEventCompletedHandler implements Runnable {

				public final void run() {
					handler.completed(buff);
				}
				
			}
			
			private final WriteEventCompletedHandler writeEventCompletedHandler = new WriteEventCompletedHandler();
			
			public final boolean handleEvent(final SocketChannel channel) throws IOException {
				channel.write(buff);
				return !buff.hasRemaining();
			}

			@Override
			public void complete() {
				executor.execute(writeEventCompletedHandler);				
			}
			
		}
		
		write(new WriteEventHandler());
	}

	@Override
	public final void write(final ByteBuffer[] buffs, final CompletionHandler<ByteBuffer[]> handler) {
		final class WriteEventHandler implements EventHandler<SocketChannel> {
			
			final class WriteEventCompletedHandler implements Runnable {

				public final void run() {
					handler.completed(buffs);
				}
				
			}
			
			private final WriteEventCompletedHandler writeEventCompletedHandler = new WriteEventCompletedHandler();
			
			public final boolean handleEvent(final SocketChannel channel) throws IOException {
				channel.write(buffs, 0, buffs.length);
				return !buffs[buffs.length - 1].hasRemaining();

			}

			@Override
			public void complete() {
				executor.execute(writeEventCompletedHandler);
			}
			
		}
		
		write(new WriteEventHandler());
	}
	
	@Override
	public final void write(final EventHandler<SocketChannel> eventHandler) {
		pendingWrites.add(eventHandler);
		enableWriteInterest();
	}
}