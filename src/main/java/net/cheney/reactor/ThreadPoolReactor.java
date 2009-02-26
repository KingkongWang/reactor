package net.cheney.reactor;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.log4j.Logger;

public final class ThreadPoolReactor extends Reactor {
	private static final Logger LOG = Logger.getLogger(ThreadPoolReactor.class);

	private final ExecutorService executor;
	private boolean running = false;
	
	private final Lock queuelock, selectorlock;

	private final Queue<Runnable> pendingInvocations = new ConcurrentLinkedQueue<Runnable>();

	protected ThreadPoolReactor(final ExecutorService executor) throws IOException {
		super();
		this.executor = executor;
		
		ReadWriteLock l = new ReentrantReadWriteLock();
		this.queuelock = l.readLock();
		this.selectorlock = l.writeLock();
	}

	public static ThreadPoolReactor open(ExecutorService executor) throws IOException {
		return new ThreadPoolReactor(executor);
	}

	public static Reactor open() throws IOException {
		return open(Executors.newCachedThreadPool());
	}

	@Override
	public final AsyncServerChannel listenTCP(final SocketAddress addr, final ServerProtocolFactory factory) throws IOException {
		final ThreadPoolAsyncServerChannel channel = new ThreadPoolAsyncServerChannel(this, factory, executor);
		return channel.listen(addr);
	}
	
	@Override
	protected Set<SelectionKey> selectNow() throws IOException {
		try {
			selectorlock.lock();
			return super.selectNow();
		} finally {
			selectorlock.unlock();
		}
	}
	
	private final void invokeLater(Runnable r) {
		pendingInvocations.add(r);
		wakeup();
	}

	@Override
	protected final void enableInterest(final SelectionKey sk, final int op) {
		if (queuelock.tryLock()) {
			enableInterestNow(sk, op);
			queuelock.unlock();
		} else {
			enableInterestLater(sk, op);
		}
	}

	private final void enableInterestLater(final SelectionKey sk, final int op) {
		invokeLater(new Runnable() {
			public void run() {
				enableInterestNow(sk, op);
			};
		});
	}
	
	private final void enableInterestNow(SelectionKey sk, int ops) {
		try {
			sk.interestOps(sk.interestOps() | ops);
		} catch (CancelledKeyException e) {
			LOG.error(String.format("Unable to set ops %d on key %s, channel %s", ops, sk, sk.channel()));
		}
	}

	@Override
	protected final void disableInterest(SelectionKey sk, int op) {
		if (queuelock.tryLock()) {
			disableInterestNow(sk, op);
			queuelock.unlock();
		} else {
			disableInterestLater(sk, op);
		}
	}

	private final void disableInterestLater(final SelectionKey sk, final int op) {
		invokeLater(new Runnable() {
			public void run() {
				disableInterestNow(sk, op);
			};
		});
	}

	private final void disableInterestNow(SelectionKey sk, int ops) {
		try {
			sk.interestOps(sk.interestOps() & ~ops);
		} catch (CancelledKeyException e) {
			LOG.error(String.format("Unable to set ops %d on key %s, channel %s", ops, sk, sk.channel()));
		}
	}

	@Override
	protected final AsyncSocketChannel newAsyncSocketChannel(final ClientProtocolFactory factory) throws IOException {
		return new ThreadPoolAsyncSocketChannel(this, factory, executor);
	}

	public final void start() {
		running = true;
		executor.execute(new Runner());
	}
	
	public final void stop() {
		running = false;
	}
	
	@Override
	public final void doSelect() throws IOException {
		doPendingInvocations();
		super.doSelect();
	}
	
	private final void doPendingInvocations() {
		for(Runnable r = pendingInvocations.poll(); r != null ; r = pendingInvocations.poll()) {
			try {
				r.run();
			} catch (CancelledKeyException ignored) {
				//
			}
		}
	}
	
	private class Runner implements Runnable {

		public void run() {
			try {
				if(running) {
					doSelect();
					executor.execute(this);
				}
			} catch (IOException e) {
				e.printStackTrace();
//				running = false;
			}
		}
		
	}

}