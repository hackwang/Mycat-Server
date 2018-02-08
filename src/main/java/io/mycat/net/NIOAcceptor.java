/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;

import io.mycat.util.SelectorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mycat.MycatServer;
import io.mycat.net.factory.FrontendConnectionFactory;

/**
 * @author mycat
 */
public final class NIOAcceptor extends Thread implements SocketAcceptor {
	private static final Logger LOGGER = LoggerFactory.getLogger(NIOAcceptor.class);
	private static final AcceptIdGenerator ID_GENERATOR = new AcceptIdGenerator();

	private final int port;
	private volatile Selector selector;
	private final ServerSocketChannel serverChannel;
	private final FrontendConnectionFactory factory;
	private long acceptCount;
	private final NIOReactorPool reactorPool;

	public NIOAcceptor(String name, String bindIp, int port, FrontendConnectionFactory factory,
			NIOReactorPool reactorPool) throws IOException {
		super.setName(name);
		this.port = port;
		// ServerSocketChannel配置，往selector上注册accept事件
		this.selector = Selector.open();
		this.serverChannel = ServerSocketChannel.open();
		this.serverChannel.configureBlocking(false);
		/** 设置TCP属性 */
		serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, 1024 * 16 * 2);
		// backlog=100
		serverChannel.bind(new InetSocketAddress(bindIp, port), 100);
		// 注册OP_ACCEPT，监听客户端连接
		this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);
		// FrontendConnectionFactory,用来封装channel成为FrontendConnection
		this.factory = factory;
		// NIOReactor池，封装请求为AbstractConnection
		this.reactorPool = reactorPool;
	}

	public int getPort() {
		return port;
	}

	public long getAcceptCount() {
		return acceptCount;
	}

	@Override
	public void run() {
		int invalidSelectCount = 0;
		for (;;) {
			final Selector tSelector = this.selector;
			++acceptCount;
			try {
				long start = System.nanoTime();
				// 轮询发现新连接请求
				tSelector.select(1000L);
				long end = System.nanoTime();
				Set<SelectionKey> keys = tSelector.selectedKeys();
				if (keys.size() == 0 && (end - start) < SelectorUtil.MIN_SELECT_TIME_IN_NANO_SECONDS) {
					invalidSelectCount++;
				} else {
					try {
						for (SelectionKey key : keys) {
							if (key.isValid() && key.isAcceptable()) {
								// 接受连接操作
								accept();
							} else {
								key.cancel();
							}
						}
					} finally {
						keys.clear();
						invalidSelectCount = 0;
					}
				}
				if (invalidSelectCount > SelectorUtil.REBUILD_COUNT_THRESHOLD) {
					final Selector rebuildSelector = SelectorUtil.rebuildSelector(this.selector);
					if (rebuildSelector != null) {
						this.selector = rebuildSelector;
					}
					invalidSelectCount = 0;
				}
			} catch (Exception e) {
				LOGGER.warn(getName(), e);
			}
		}
	}

	private void accept() {
		SocketChannel channel = null;
		try {
			channel = serverChannel.accept();
			// 得到通信channel并设置为非阻塞
			channel.configureBlocking(false);
			// 封装channel为FrontendConnection
			FrontendConnection c = factory.make(channel);
			c.setAccepted(true);
			c.setId(ID_GENERATOR.getId());
			// 利用NIOProcessor管理前端链接，定期清除空闲连接，同时做写队列检查
			NIOProcessor processor = (NIOProcessor) MycatServer.getInstance().nextProcessor();
			c.setProcessor(processor);

			// 和具体执行selector响应感兴趣事件的NIOReactor绑定
			NIOReactor reactor = reactorPool.getNextReactor();
			reactor.postRegister(c);

		} catch (Exception e) {
			LOGGER.warn(getName(), e);
			closeChannel(channel);
		}
	}

	private static void closeChannel(SocketChannel channel) {
		if (channel == null) {
			return;
		}
		Socket socket = channel.socket();
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException e) {
				LOGGER.error("closeChannelError", e);
			}
		}
		try {
			channel.close();
		} catch (IOException e) {
			LOGGER.error("closeChannelError", e);
		}
	}

	/**
	 * 前端连接ID生成器
	 * 
	 * @author mycat
	 */
	private static class AcceptIdGenerator {

		private static final long MAX_VALUE = 0xffffffffL;

		private long acceptId = 0L;
		private final Object lock = new Object();

		private long getId() {
			synchronized (lock) {
				if (acceptId >= MAX_VALUE) {
					acceptId = 0L;
				}
				return ++acceptId;
			}
		}
	}

}
