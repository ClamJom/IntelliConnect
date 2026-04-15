/**
 * Copyright © 2023-2030 The ruanrongman Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package top.rslly.iot.utility.smartVoice;

import lombok.Getter;

import java.util.concurrent.*;

/**
 * 流式响应的会话级上下文对象，封装了线程间通信所需的阻塞队列和同步工具。
 * <p>
 * 替代原先通过 Redis List / Redis Hash 进行的进程内线程间状态管理，
 * 消除忙等待循环，降低 CPU 和 Redis 开销。
 * </p>
 * <p>
 * TTS 顺序保证机制：主线程按句子顺序将 {@link CompletableFuture} 放入
 * {@code orderedFutures} 队列，asyncTTS 虚拟线程完成后 complete 对应的 future，
 * streamRspResultHandler 按 FIFO 顺序逐个 get()，保证严格有序播放。
 * </p>
 */
@Getter
public class StreamContext {

  private final String chatId;
  private final int productId;

  /**
   * 有序的 TTS future 队列：主线程按句子顺序 offer，消费者线程按 FIFO 顺序 poll + get。
   * 即使并行 TTS 完成顺序不同，消费者也会按原始顺序等待并播放。
   */
  private final BlockingQueue<CompletableFuture<SentenceAudio>> orderedFutures =
      new LinkedBlockingQueue<>();

  /**
   * 结果处理线程结束信号：streamRspResultHandler 退出时 countDown，
   * handlerStreamRsp 主线程通过 await 等待。
   */
  private final CountDownLatch resultHandlerDone = new CountDownLatch(1);

  /**
   * 中断标志，由外部设置，各循环检查此标志以提前退出。
   */
  private volatile boolean aborted = false;

  public StreamContext(String chatId, int productId) {
    this.chatId = chatId;
    this.productId = productId;
  }

  // ---- 有序 TTS future 队列操作 ----

  /**
   * 主线程按句子顺序将 future 放入队列（非阻塞）。
   * 必须在启动 asyncTTS 虚拟线程之前或同时调用，以保证顺序。
   */
  public void offerFuture(CompletableFuture<SentenceAudio> future) {
    orderedFutures.offer(future);
  }

  /**
   * 消费者线程按 FIFO 顺序取出下一个 future，超时返回 null。
   */
  public CompletableFuture<SentenceAudio> pollFuture(long timeout, TimeUnit unit)
      throws InterruptedException {
    return orderedFutures.poll(timeout, unit);
  }

  // ---- 线程同步 ----

  /**
   * 等待结果处理线程结束，最多等待指定时间。
   *
   * @return 如果在超时前线程结束返回 true，否则返回 false
   */
  public boolean awaitResultHandlerDone(long timeout, TimeUnit unit) throws InterruptedException {
    return resultHandlerDone.await(timeout, unit);
  }

  /**
   * 由结果处理线程调用，通知主线程自己已结束。
   */
  public void signalResultHandlerDone() {
    resultHandlerDone.countDown();
  }

  // ---- 中断控制 ----

  public void abort() {
    this.aborted = true;
  }
}
