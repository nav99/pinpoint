/*
 * Copyright 2014 NAVER Corp.
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

package com.navercorp.pinpoint.profiler.modifier.arcus.interceptor;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import com.navercorp.pinpoint.bootstrap.context.AsyncTrace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.interceptor.ByteCodeMethodDescriptorSupport;
import com.navercorp.pinpoint.bootstrap.interceptor.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.interceptor.SimpleAroundInterceptor;
import com.navercorp.pinpoint.bootstrap.interceptor.TraceContextSupport;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.util.MetaObject;
import com.navercorp.pinpoint.bootstrap.util.TimeObject;
import com.navercorp.pinpoint.common.AnnotationKey;
import com.navercorp.pinpoint.common.ServiceType;

import net.spy.memcached.MemcachedNode;
import net.spy.memcached.ops.OperationState;
import net.spy.memcached.protocol.BaseOperationImpl;

/**
 * @author emeroad
 */
@Deprecated
public class BaseOperationTransitionStateInterceptor implements SimpleAroundInterceptor, ByteCodeMethodDescriptorSupport, TraceContextSupport {

    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final boolean isDebug = logger.isDebugEnabled();

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private MetaObject getAsyncTrace = new MetaObject("__getAsyncTrace");
    private MetaObject getServiceCode = new MetaObject("__getServiceCode");

    private MethodDescriptor methodDescriptor;
    private TraceContext traceContext;

    @Override
    public void before(Object target, Object[] args) {
        if (isDebug) {
            logger.beforeInterceptor(target, args);
        }

        AsyncTrace asyncTrace = (AsyncTrace) getAsyncTrace.invoke(target);
        if (asyncTrace == null) {
            if (isDebug) {
                logger.debug("asyncTrace not found");
            }
            return;
        }
        // TODO Don't we have to check null? Don't fix now because this interceptor is deprecated.
        OperationState newState = (OperationState) args[0];

        BaseOperationImpl baseOperation = (BaseOperationImpl) target;
        if (newState == OperationState.READING) {
            if (isDebug) {
                logger.debug("event:{} asyncTrace:{}", newState, asyncTrace);
            }
            if (asyncTrace.getState() != AsyncTrace.STATE_INIT) {
                return;
            }
            MemcachedNode handlingNode = baseOperation.getHandlingNode();
            SocketAddress socketAddress = handlingNode.getSocketAddress();
            if (socketAddress instanceof InetSocketAddress) {
                InetSocketAddress address = (InetSocketAddress) socketAddress;
                asyncTrace.recordEndPoint(address.getHostName() + ":" + address.getPort());
            }

            String serviceCode = (String) getServiceCode.invoke(target);

            if (serviceCode == null) {
                serviceCode = "UNKNOWN";
            }

            ServiceType svcType = ServiceType.ARCUS;

            if(serviceCode.equals(ServiceType.MEMCACHED.getDesc())) {
                svcType = ServiceType.MEMCACHED;
            }

            asyncTrace.recordServiceType(svcType);
//            asyncTrace.recordRpcName(baseOperation.getClass().getSimpleName());
            asyncTrace.recordApi(methodDescriptor);

            asyncTrace.recordDestinationId(serviceCode);

            String cmd = getCommand(baseOperation);
//            asyncTrace.recordAttribute(AnnotationKey.ARCUS_COMMAND, cmd);

            // TimeObject timeObject = (TimeObject)
            // asyncTrace.getFrameObject();
            // timeObject.markSendTime();

            // long createTime = asyncTrace.getBeforeTime();
            asyncTrace.markAfterTime();
//            asyncTrace.traceBlockEnd();
        } else if (newState == OperationState.COMPLETE || isArcusTimeout(newState)) {
            if (isDebug) {
                logger.debug("event:{} asyncTrace:{}", newState, asyncTrace);
            }
            boolean fire = asyncTrace.fire();
            if (!fire) {
                return;
            }
            Exception exception = baseOperation.getException();
            asyncTrace.recordException(exception);

            if (!baseOperation.isCancelled()) {
                TimeObject timeObject = (TimeObject) asyncTrace.getAttachObject();
                // asyncTrace.record(Annotation.ClientRecv, timeObject.getSendTime());
                asyncTrace.markAfterTime();
                asyncTrace.traceBlockEnd();
            } else {
                asyncTrace.recordAttribute(AnnotationKey.EXCEPTION, "cancelled by user");
                TimeObject timeObject = (TimeObject) asyncTrace.getAttachObject();
                // asyncTrace.record(Annotation.ClientRecv, timeObject.getCancelTime());
                asyncTrace.markAfterTime();
                asyncTrace.traceBlockEnd();
            }
        }
    }

    private boolean isArcusTimeout(OperationState newState) {
        if (newState == null) {
            return false;
        }
        
        // Check Arcus only state 
        return "TIMEDOUT".equals(newState.toString());
    }

    private String getCommand(BaseOperationImpl baseOperation) {
        ByteBuffer buffer = baseOperation.getBuffer();
        if (buffer == null) {
            return "UNKNOWN";
        }
        // System.out.println(buffer.array().length + " po:" + buffer.position()
        // + " limit:" + buffer.limit() + " remaining"
        // + buffer.remaining() + " aoffset:" + buffer.arrayOffset());
        return new String(buffer.array(), UTF8);
    }

    @Override
    public void setMethodDescriptor(MethodDescriptor descriptor) {
        this.methodDescriptor = descriptor;
        this.traceContext.cacheApi(descriptor);
    }

    @Override
    public void setTraceContext(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
    }
}
