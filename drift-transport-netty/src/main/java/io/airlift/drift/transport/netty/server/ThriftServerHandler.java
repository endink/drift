/*
 * Copyright (C) 2013 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.airlift.drift.transport.netty.server;

import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Primitives;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.drift.TApplicationException;
import io.airlift.drift.codec.ThriftCodec;
import io.airlift.drift.codec.internal.ProtocolReader;
import io.airlift.drift.codec.internal.ProtocolWriter;
import io.airlift.drift.protocol.TMessage;
import io.airlift.drift.protocol.TMessageType;
import io.airlift.drift.protocol.TProtocol;
import io.airlift.drift.protocol.TProtocolFactory;
import io.airlift.drift.transport.MethodMetadata;
import io.airlift.drift.transport.ParameterMetadata;
import io.airlift.drift.transport.netty.TChannelBufferInputTransport;
import io.airlift.drift.transport.netty.TChannelBufferOutputTransport;
import io.airlift.drift.transport.server.ServerInvokeRequest;
import io.airlift.drift.transport.server.ServerMethodInvoker;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.base.Defaults.defaultValue;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.airlift.drift.TApplicationException.Type.INTERNAL_ERROR;
import static io.airlift.drift.TApplicationException.Type.INVALID_MESSAGE_TYPE;
import static io.airlift.drift.TApplicationException.Type.UNKNOWN_METHOD;
import static io.airlift.drift.protocol.TMessageType.EXCEPTION;
import static io.airlift.drift.protocol.TMessageType.REPLY;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ThriftServerHandler
        extends ChannelDuplexHandler
{
    private static final Logger log = Logger.get(ThriftServerHandler.class);

    private final ServerMethodInvoker methodInvoker;
    private final ScheduledExecutorService timeoutExecutor;
    private final Duration requestTimeout;

    public ThriftServerHandler(ServerMethodInvoker methodInvoker, Duration requestTimeout, ScheduledExecutorService timeoutExecutor)
    {
        this.methodInvoker = requireNonNull(methodInvoker, "methodInvoker is null");
        this.requestTimeout = requireNonNull(requestTimeout, "requestTimeout is null");
        this.timeoutExecutor = requireNonNull(timeoutExecutor, "timeoutExecutor is null");
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message)
            throws Exception
    {
        if (message instanceof ThriftFrame) {
            messageReceived(context, (ThriftFrame) message);
            return;
        }
        context.fireChannelRead(message);
    }

    private void messageReceived(ChannelHandlerContext context, ThriftFrame frame)
    {
        try {
            ListenableFuture<ThriftFrame> response = decodeMessage(
                    context,
                    frame.getProtocolFactory(),
                    frame.getMessage(),
                    frame.getHeaders(),
                    frame.isSupportOutOfOrderResponse());
            Futures.addCallback(response, new FutureCallback<ThriftFrame>()
            {
                @Override
                public void onSuccess(ThriftFrame result)
                {
                    context.writeAndFlush(result);
                }

                @Override
                public void onFailure(Throwable t)
                {
                    context.disconnect();
                }
            });
        }
        catch (Exception e) {
            log.error(e, "Exception processing request");
            context.disconnect();
        }
        catch (Throwable e) {
            log.error(e, "Error processing request");
            context.disconnect();
            throw e;
        }
    }

    private ListenableFuture<ThriftFrame> decodeMessage(
            ChannelHandlerContext context,
            TProtocolFactory protocolFactory,
            ByteBuf frame,
            Map<String, String> headers,
            boolean supportOutOfOrderResponse)
            throws Exception
    {
        long start = System.nanoTime();
        TProtocol protocol = protocolFactory.getProtocol(new TChannelBufferInputTransport(frame));

        TMessage message = protocol.readMessageBegin();
        Optional<MethodMetadata> methodMetadata = methodInvoker.getMethodMetadata(message.getName());
        if (!methodMetadata.isPresent()) {
            return immediateFuture(writeApplicationException(
                    context,
                    message.getName(),
                    protocolFactory,
                    message.getSequenceId(),
                    supportOutOfOrderResponse,
                    UNKNOWN_METHOD,
                    "Invalid method name: '" + message.getName() + "'",
                    null));
        }
        MethodMetadata method = methodMetadata.get();

        if (message.getType() != TMessageType.CALL && message.getType() != TMessageType.ONEWAY) {
            return immediateFuture(writeApplicationException(
                    context,
                    message.getName(),
                    protocolFactory,
                    message.getSequenceId(),
                    supportOutOfOrderResponse,
                    INVALID_MESSAGE_TYPE,
                    "Invalid method message type: '" + message.getType() + "'",
                    null));
        }

        List<Object> parameters = readArguments(method, protocol);

        ListenableFuture<Object> result = methodInvoker.invoke(new ServerInvokeRequest(method, headers, parameters));
        methodInvoker.recordResult(message.getName(), start, result);
        ListenableFuture<ThriftFrame> encodedResult = Futures.transformAsync(result, value -> {
            try {
                return immediateFuture(writeSuccessResponse(context, method, protocolFactory, message.getSequenceId(), supportOutOfOrderResponse, value));
            }
            catch (Exception e) {
                return immediateFailedFuture(e);
            }
        });
        encodedResult = Futures.withTimeout(encodedResult, requestTimeout.toMillis(), MILLISECONDS, timeoutExecutor);
        encodedResult = Futures.catchingAsync(encodedResult, Exception.class, exception -> {
            try {
                return immediateFuture(writeExceptionResponse(context, method, protocolFactory, message.getSequenceId(), supportOutOfOrderResponse, exception));
            }
            catch (Exception e) {
                return immediateFailedFuture(e);
            }
        });
        return encodedResult;
    }

    private static List<Object> readArguments(MethodMetadata method, TProtocol protocol)
            throws Exception
    {
        Object[] arguments = new Object[method.getParameters().size()];
        ProtocolReader reader = new ProtocolReader(protocol);

        reader.readStructBegin();
        while (reader.nextField()) {
            short fieldId = reader.getFieldId();

            ParameterMetadata parameter = method.getParameterByFieldId(fieldId);
            if (parameter == null) {
                reader.skipFieldData();
            }
            else {
                arguments[parameter.getIndex()] = reader.readField(parameter.getCodec());
            }
        }
        reader.readStructEnd();

        // set defaults for missing arguments
        for (ParameterMetadata parameter : method.getParameters()) {
            if (arguments[parameter.getIndex()] == null) {
                Type argumentType = parameter.getCodec().getType().getJavaType();

                if (argumentType instanceof Class) {
                    Class<?> argumentClass = (Class<?>) argumentType;
                    argumentClass = Primitives.unwrap(argumentClass);
                    arguments[parameter.getIndex()] = defaultValue(argumentClass);
                }
            }
        }

        return asList(arguments);
    }

    private static ThriftFrame writeSuccessResponse(
            ChannelHandlerContext context,
            MethodMetadata methodMetadata,
            TProtocolFactory protocolFactory,
            int sequenceId,
            boolean supportOutOfOrderResponse,
            Object result)
            throws Exception
    {
        ByteBuf buffer = context.alloc().buffer(1024);
        TChannelBufferOutputTransport transport = new TChannelBufferOutputTransport(buffer);
        TProtocol protocol = protocolFactory.getProtocol(transport);

        writeResponse(methodMetadata.getName(), protocol, sequenceId, "success", (short) 0, methodMetadata.getResultCodec(), result);

        return new ThriftFrame(
                OptionalInt.of(sequenceId),
                buffer,
                ImmutableMap.of(),
                protocolFactory,
                supportOutOfOrderResponse);
    }

    private static ThriftFrame writeExceptionResponse(ChannelHandlerContext context,
            MethodMetadata methodMetadata,
            TProtocolFactory protocolFactory,
            int sequenceId,
            boolean supportOutOfOrderResponse,
            Throwable exception)
            throws Exception
    {
        Optional<Short> exceptionId = methodMetadata.getExceptionId(exception.getClass());
        if (exceptionId.isPresent()) {
            ByteBuf buffer = context.alloc().buffer(1024);
            TChannelBufferOutputTransport transport = new TChannelBufferOutputTransport(buffer);
            TProtocol protocol = protocolFactory.getProtocol(transport);

            writeResponse(
                    methodMetadata.getName(),
                    protocol,
                    sequenceId,
                    "exception",
                    exceptionId.get(),
                    methodMetadata.getExceptionCodecs().get(exceptionId.get()),
                    exception);

            return new ThriftFrame(
                    OptionalInt.of(sequenceId),
                    buffer,
                    ImmutableMap.of(),
                    protocolFactory,
                    supportOutOfOrderResponse);
        }

        return writeApplicationException(
                context,
                methodMetadata.getName(),
                protocolFactory,
                sequenceId,
                supportOutOfOrderResponse,
                INTERNAL_ERROR,
                "Internal error processing " + methodMetadata.getName() + ": " + exception.getMessage(),
                exception);
    }

    private static ThriftFrame writeApplicationException(
            ChannelHandlerContext context,
            String methodName,
            TProtocolFactory protocolFactory,
            int sequenceId,
            boolean supportOutOfOrderResponse,
            TApplicationException.Type errorCode,
            String errorMessage,
            Throwable cause)
            throws Exception
    {
        TApplicationException applicationException = new TApplicationException(errorCode, errorMessage);
        if (cause != null) {
            applicationException.initCause(cause);
        }

        TChannelBufferOutputTransport transport = new TChannelBufferOutputTransport(context.alloc().buffer(1024));
        TProtocol protocol = protocolFactory.getProtocol(transport);

        protocol.writeMessageBegin(new TMessage(methodName, EXCEPTION, sequenceId));

        ExceptionWriter.writeTApplicationException(applicationException, protocol);

        protocol.writeMessageEnd();
        return new ThriftFrame(
                OptionalInt.of(sequenceId),
                transport.getOutputBuffer(),
                ImmutableMap.of(),
                protocolFactory,
                supportOutOfOrderResponse);
    }

    private static void writeResponse(
            String methodName,
            TProtocol protocol,
            int sequenceId,
            String responseFieldName,
            short responseFieldId,
            ThriftCodec<Object> responseCodec,
            Object result)
            throws Exception
    {
        protocol.writeMessageBegin(new TMessage(methodName, REPLY, sequenceId));

        ProtocolWriter writer = new ProtocolWriter(protocol);
        writer.writeStructBegin(methodName + "_result");
        writer.writeField(responseFieldName, responseFieldId, responseCodec, result);
        writer.writeStructEnd();

        protocol.writeMessageEnd();
    }
}
