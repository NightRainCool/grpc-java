/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.transport;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;

import io.grpc.transport.ClientTransport.PingCallback;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.concurrent.GuardedBy;

/**
 * Represents an outstanding PING operation on an HTTP/2 channel. This can be used by HTTP/2-based
 * transports to implement {@link ClientTransport#ping}.
 *
 * <p>A typical transport need only support one outstanding ping at a time. So, if a ping is
 * requested while an operation is already in progress, the given callback is notified when the
 * existing operation completes.
 */
public class Http2Ping {
  private static final Logger log = Logger.getLogger(Http2Ping.class.getName());

  /**
   * The PING frame includes 8 octets of payload data, e.g. 64 bits.
   */
  private final long data;

  /**
   * Used to measure elapsed time.
   */
  private final Stopwatch stopwatch;

  /**
   * The registered callbacks and the executor used to invoke them.
   */
  @GuardedBy("this") private Map<PingCallback, Executor> callbacks = Maps.newLinkedHashMap();

  /**
   * False until the operation completes, either successfully (other side sent acknowledgement) or
   * unsuccessfully.
   */
  @GuardedBy("this") private boolean completed;

  /**
   * If non-null, indicates the ping failed.
   */
  @GuardedBy("this") private Throwable failureCause;

  /**
   * The round-trip time for the ping, in microseconds. This value is only meaningful when
   * {@link #completed} is true and {@link #failureCause} is null.
   */
  @GuardedBy("this") private long roundTripTimeMicros;

  /**
   * Creates a new ping operation. The caller is responsible for sending a ping on an HTTP/2 channel
   * using the given payload. The caller is also responsible for starting the stopwatch when the
   * PING frame is sent.
   *
   * @param data the ping payload
   * @param stopwatch a stopwatch for measuring round-trip time
   */
  public Http2Ping(long data, Stopwatch stopwatch) {
    this.data = data;
    this.stopwatch = stopwatch;
  }

  /**
   * Registers a callback that is invoked when the ping operation completes. If this ping operation
   * is already completed, the callback is invoked immediately.
   *
   * @param callback the callback to invoke
   * @param executor the executor to use
   */
  public void addCallback(final ClientTransport.PingCallback callback, Executor executor) {
    Runnable runnable;
    synchronized (this) {
      if (!completed) {
        callbacks.put(callback, executor);
        return;
      }
      // otherwise, invoke callback immediately (but not while holding lock)
      runnable = this.failureCause != null ? asRunnable(callback, failureCause)
                                           : asRunnable(callback, roundTripTimeMicros);
    }
    doExecute(executor, runnable);
  }

  /**
   * Returns the expected ping payload for this outstanding operation.
   *
   * @return the expected payload for this outstanding ping
   */
  public long payload() {
    return data;
  }

  /**
   * Completes this operation successfully. The stopwatch given during construction is used to
   * measure the elapsed time. Registered callbacks are invoked and provided the measured elapsed
   * time.
   *
   * @return true if the operation is marked as complete; false if it was already complete
   */
  public boolean complete() {
    Map<ClientTransport.PingCallback, Executor> callbacks;
    long roundTripTimeMicros;
    synchronized (this) {
      if (completed) {
        return false;
      }
      completed = true;
      roundTripTimeMicros = this.roundTripTimeMicros = stopwatch.elapsed(TimeUnit.MICROSECONDS);
      callbacks = this.callbacks;
      this.callbacks = null;
    }
    for (Map.Entry<ClientTransport.PingCallback, Executor> entry : callbacks.entrySet()) {
      doExecute(entry.getValue(), asRunnable(entry.getKey(), roundTripTimeMicros));
    }
    return true;
  }

  /**
   * Completes this operation exceptionally. Registered callbacks are invoked and provided the
   * given throwable as the cause of failure.
   *
   * @param failureCause the cause of failure
   */
  public void failed(Throwable failureCause) {
    Map<ClientTransport.PingCallback, Executor> callbacks;
    synchronized (this) {
      if (completed) {
        return;
      }
      completed = true;
      this.failureCause = failureCause;
      callbacks = this.callbacks;
      this.callbacks = null;
    }
    for (Map.Entry<ClientTransport.PingCallback, Executor> entry : callbacks.entrySet()) {
      doExecute(entry.getValue(), asRunnable(entry.getKey(), failureCause));
    }
  }

  /**
   * Notifies the given callback that the ping operation failed.
   *
   * @param callback the callback
   * @param executor the executor used to invoke the callback
   * @param cause the cause of failure
   */
  public static void notifyFailed(PingCallback callback, Executor executor, Throwable cause) {
    doExecute(executor, asRunnable(callback, cause));
  }

  /**
   * Executes the given runnable. This prevents exceptions from propagating so that an exception
   * thrown by one callback won't prevent subsequent callbacks from being executed.
   */
  private static void doExecute(Executor executor, Runnable runnable) {
    try {
      executor.execute(runnable);
    } catch (Throwable th) {
      log.log(Level.SEVERE, "Failed to execute PingCallback", th);
    }
  }

  /**
   * Returns a runnable that, when run, invokes the given callback, providing the given round-trip
   * duration.
   */
  private static Runnable asRunnable(final ClientTransport.PingCallback callback,
                                     final long roundTripTimeMicros) {
    return new Runnable() {
      @Override
      public void run() {
        callback.pingAcknowledged(roundTripTimeMicros);
      }
    };

  }

  /**
   * Returns a runnable that, when run, invokes the given callback, providing the given cause of
   * failure.
   */
  private static Runnable asRunnable(final ClientTransport.PingCallback callback,
                                     final Throwable failureCause) {
    return new Runnable() {
      @Override
      public void run() {
        callback.pingFailed(failureCause);
      }
    };
  }
}
