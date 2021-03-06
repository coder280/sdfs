/*******************************************************************************
 * Copyright (C) 2016 Sam Silverberg sam.silverberg@gmail.com	
 *
 * This file is part of OpenDedupe SDFS.
 *
 * OpenDedupe SDFS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * OpenDedupe SDFS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package org.opendedup.hashing;

import java.security.NoSuchAlgorithmException;

import java.util.ArrayList;

import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

import org.opendedup.collections.QuickList;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.io.BufferClosedException;
import org.opendedup.sdfs.io.HashLocPair;
import org.opendedup.sdfs.io.WritableCacheBuffer;
import org.opendedup.sdfs.servers.HCServiceProxy;

public class PoolThread implements AbstractPoolThread, Runnable {

	private BlockingQueue<WritableCacheBuffer> taskQueue = null;
	private boolean isStopped = false;
	private static int maxTasks = ((Main.maxWriteBuffers * 1024 * 1024) / (Main.CHUNK_LENGTH)) + 1;
	// private static final int maxTasks = 40;
	private final QuickList<WritableCacheBuffer> tasks = new QuickList<WritableCacheBuffer>(
			maxTasks);
	Thread th = null;
	public static VariableHashEngine eng = null;

	static {
		if (maxTasks > 120)
			maxTasks = 120;
		SDFSLogger.getLog().debug("Pool List Size will be " + maxTasks);
		if (HashFunctionPool.max_hash_cluster > 1) {
			try {
				eng = new VariableHashEngine();
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
				System.exit(1);
			}
		}
	}

	public PoolThread(BlockingQueue<WritableCacheBuffer> queue) {
		taskQueue = queue;
	}

	@Override
	public void run() {
		while (!isStopped()) {
			try {
				tasks.clear();
				int ts = taskQueue.drainTo(tasks, maxTasks);
				if (ts > 0) {
					if (Main.chunkStoreLocal) {
						for (int i = 0; i < ts; i++) {
							WritableCacheBuffer runnable = tasks.get(i);
							try {
								runnable.close();
							} catch (Exception e) {
								SDFSLogger.getLog().fatal(
										"unable to execute thread", e);
							}
						}
					} else {

						if (HashFunctionPool.max_hash_cluster == 1) {
							for (int i = 0; i < ts; i++) {
								WritableCacheBuffer runnable = tasks.get(i);
								runnable.startClose();

								byte[] hash = null;
								AbstractHashEngine hc = HashFunctionPool
										.borrowObject();
								try {

									byte[] b = runnable.getFlushedBuffer();
									hash = hc.getHash(b);
									TreeMap<Integer,HashLocPair> ar = new TreeMap<Integer,HashLocPair>();
									HashLocPair p = new HashLocPair();
									p.hash = hash;
									p.pos = 0;
									p.len = b.length;
									p.nlen = b.length;
									p.hashloc = new byte[8];
									p.hash = hash;
									p.data = b;
									ar.put(p.pos,p);
									runnable.setAR(ar);
								} catch (BufferClosedException e) {

								} finally {
									HashFunctionPool.returnObject(hc);
								}
							}
						} else {
							for (int i = 0; i < ts; i++) {
								WritableCacheBuffer writeBuffer = tasks.get(i);
								writeBuffer.startClose();

								List<Finger> fs = eng.getChunks(writeBuffer
										.getFlushedBuffer());
								TreeMap<Integer,HashLocPair> ar = new TreeMap<Integer,HashLocPair>();
								for (Finger f : fs) {
									HashLocPair p = new HashLocPair();
									p.hash = f.hash;
									p.hashloc = new byte[8];
									p.len = f.len;
									p.offset = 0;
									p.nlen = f.len;
									p.data = f.chunk;
									p.pos = f.start;
									ar.put(p.pos,p);
								}
								writeBuffer.setAR(ar);
							}
						}
						ArrayList<HashLocPair> al = new ArrayList<HashLocPair>();

						for (int i = 0; i < tasks.size(); i++) {
							WritableCacheBuffer ck = tasks.get(i);
							if (ck == null)
								break;
							else
								al.addAll(ck.getFingers().values());
						}

						HCServiceProxy.batchHashExists(al);
						for (int i = 0; i < ts; i++) {
							WritableCacheBuffer runnable = tasks.get(i);
							if (runnable != null) {

								try {
									runnable.endClose();
								} catch (Exception e) {
									SDFSLogger.getLog().warn(
											"unable to close block", e);
								}
							}
						}
					}
				} else {
					Thread.sleep(1);
				}

			} catch (Exception e) {
				SDFSLogger.getLog().fatal("unable to execute thread", e);
			}
		}
	}

	private ReentrantLock exitLock = new ReentrantLock();

	public void start() {
		th = new Thread(this);
		th.start();
	}

	public void exit() {
		exitLock.lock();
		try {
			isStopped = true;

			th.interrupt(); // break pool thread out of dequeue() call.
		} catch (Exception e) {
		} finally {
			exitLock.unlock();
		}
	}

	public boolean isStopped() {
		return isStopped;
	}

}
