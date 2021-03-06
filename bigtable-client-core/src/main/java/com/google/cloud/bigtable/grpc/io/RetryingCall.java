/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigtable.grpc.io;

import com.google.api.client.util.BackOff;
import com.google.cloud.bigtable.config.RetryOptions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A Call that reties lower-level Calls that fail with INTERNAL errors.
 * @param <RequestT> The type of the request message
 * @param <ResponseT> The type of the response message
 */
public class RetryingCall<RequestT, ResponseT> extends ClientCall<RequestT, ResponseT> {

  private final Channel channel;
  private final MethodDescriptor<RequestT, ResponseT> method;
  private final CallOptions callOptions;
  private final RetryOptions retryOptions;
  private BackOff backOff;
  private final ScheduledExecutorService scheduledExecutorService;

  private Listener<ResponseT> listener;
  private Metadata headers;
  private RequestT message;
  private boolean payloadIsRetriable = true;
  private final SettableFuture<Void> cancelled = SettableFuture.create();

  public RetryingCall(
      Channel channel,
      MethodDescriptor<RequestT, ResponseT> method,
      CallOptions callOptions,
      ScheduledExecutorService scheduledExecutorService,
      RetryOptions retryOptions) {
    this.channel = channel;
    this.method = method;
    this.callOptions = callOptions;
    this.scheduledExecutorService = scheduledExecutorService;
    this.retryOptions = retryOptions;
  }

  @Override
  public void start(Listener<ResponseT> listener, Metadata headers) {
    Preconditions.checkState(
        this.listener == null,
        "start should not be invoked more than once for unary calls.");
    this.listener = new RetryListener<>(this, message, headers, payloadIsRetriable,
        listener);
    this.headers = headers;
  }

  @Override
  public void request(int numMessages) {
    // Ignoring flow control since this is a unary call. This is not exactly
    // compliant with the Reactive-Streams-style flow control API in that
    // we'll still deliver the message even if it wasn't requested, but this
    // isn't a concern for Cloud Bigtable.
  }

  @Override
  public void cancel() {
    // This will call cancel on *all* delegate calls
    cancelled.set(null);
  }

  @Override
  public void sendMessage(RequestT message) {
    Preconditions.checkState(this.message == null,
      "sendPayload should not be invoked more than once for unary calls.");
    this.message = message;
  }

  @Override
  public void halfClose() {
    retryCall(message, headers, listener);
  }

  // retryCall can be invoked from any thread.
  private void retryCall(
      RequestT message,
      Metadata requestHeaders,
      Listener<ResponseT> listener) {
    final ClientCall<RequestT, ResponseT> delegate = channel.newCall(method, callOptions);
    delegate.start(listener, requestHeaders);
    delegate.request(1);
    cancelled.addListener(new Runnable() {
      @Override
      public void run() {
        delegate.cancel();
      }
    }, MoreExecutors.directExecutor());

    delegate.sendMessage(message);
    delegate.halfClose();
  }

  // Always called from the listener.
  @VisibleForTesting
  synchronized boolean retryCallAfterBackoff(
      final RequestT payload,
      final Metadata requestHeaders,
      final Listener<ResponseT> listener) {
    long sleepTimeout = BackOff.STOP;
    if (backOff == null) {
      backOff = retryOptions.createBackoff();
    }
    try {
      sleepTimeout = backOff.nextBackOffMillis();
    } catch (IOException e) {
      // Ignored, we will not retry and close will bubble outward
    }
    if (sleepTimeout != BackOff.STOP) {
      scheduledExecutorService.schedule(new Runnable() {
        @Override
        public void run() {
          try {
            retryCall(payload, requestHeaders, listener);
          } catch (RuntimeException e) {
            Metadata trailers = new Metadata();
            listener.onClose(Status.fromThrowable(e), trailers);
          }
        }
      }, sleepTimeout, TimeUnit.MILLISECONDS);
      // We've scheduled a retry
      return true;
    }
    // We are NOT retrying
    return false;
  }

  @Override
  public boolean isReady() {
    // TODO: This should be a more sophisticated check.  This should work for now, since this is a
    // unary call.
    return true;
  }
}
