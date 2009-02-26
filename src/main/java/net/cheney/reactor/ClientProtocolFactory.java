package net.cheney.reactor;

import net.cheney.reactor.AsyncChannel.CompletionHandler;

public abstract class ClientProtocolFactory implements CompletionHandler<AsyncSocketChannel> {

	public final void completed(final AsyncSocketChannel channel) {
		onConnect(channel);
	}

	protected abstract void onConnect(final AsyncSocketChannel channel);

}