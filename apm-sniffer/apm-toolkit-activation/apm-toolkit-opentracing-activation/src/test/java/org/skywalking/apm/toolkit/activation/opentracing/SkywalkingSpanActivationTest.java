package org.skywalking.apm.toolkit.activation.opentracing;

import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import java.util.HashMap;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.skywalking.apm.agent.core.context.ContextCarrier;
import org.skywalking.apm.agent.core.context.ContextSnapshot;
import org.skywalking.apm.agent.core.context.trace.AbstractTracingSpan;
import org.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.skywalking.apm.agent.core.context.trace.TraceSegmentRef;
import org.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.skywalking.apm.agent.test.helper.SegmentHelper;
import org.skywalking.apm.agent.test.helper.SegmentRefHelper;
import org.skywalking.apm.agent.test.tools.AgentServiceRule;
import org.skywalking.apm.agent.test.tools.SegmentStorage;
import org.skywalking.apm.agent.test.tools.SegmentStoragePoint;
import org.skywalking.apm.agent.test.tools.TracingSegmentRunner;
import org.skywalking.apm.toolkit.activation.opentracing.continuation.ActivateInterceptor;
import org.skywalking.apm.toolkit.activation.opentracing.continuation.ConstructorInterceptor;
import org.skywalking.apm.toolkit.activation.opentracing.span.ConstructorWithSpanBuilderInterceptor;
import org.skywalking.apm.toolkit.activation.opentracing.span.SpanFinishInterceptor;
import org.skywalking.apm.toolkit.activation.opentracing.span.SpanLogInterceptor;
import org.skywalking.apm.toolkit.activation.opentracing.span.SpanSetOperationNameInterceptor;
import org.skywalking.apm.toolkit.activation.opentracing.tracer.SkywalkingTracerExtractInterceptor;
import org.skywalking.apm.toolkit.activation.opentracing.tracer.SkywalkingTracerInjectInterceptor;
import org.skywalking.apm.toolkit.opentracing.SkywalkingSpanBuilder;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.skywalking.apm.agent.test.tools.SegmentRefAssert.assertPeerHost;
import static org.skywalking.apm.agent.test.tools.SegmentRefAssert.assertSegmentId;
import static org.skywalking.apm.agent.test.tools.SegmentRefAssert.assertEntryApplicationInstanceId;
import static org.skywalking.apm.agent.test.tools.SegmentRefAssert.assertSpanId;
import static org.skywalking.apm.agent.test.tools.SpanAssert.assertComponent;
import static org.skywalking.apm.agent.test.tools.SpanAssert.assertLogSize;

@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(TracingSegmentRunner.class)
public class SkywalkingSpanActivationTest {

    @SegmentStoragePoint
    private SegmentStorage storage;

    @Rule
    public AgentServiceRule serviceRule = new AgentServiceRule();
    private MockEnhancedInstance enhancedInstance = new MockEnhancedInstance();
    private ConstructorWithSpanBuilderInterceptor constructorWithSpanBuilderInterceptor;
    private Tracer.SpanBuilder spanBuilder = new SkywalkingSpanBuilder("test");
    private SpanLogInterceptor spanLogInterceptor;
    private Object[] logArgument;
    private HashMap<String, Object> event = new HashMap<String, Object>() {
        {
            put("a", "A");
        }
    };
    private Class[] logArgumentType;

    private SpanSetOperationNameInterceptor setOperationNameInterceptor;
    private Object[] setOperationNameArgument;
    private Class[] setOperationNameArgumentType;

    private SpanFinishInterceptor spanFinishInterceptor;

    private SkywalkingTracerInjectInterceptor injectInterceptor;

    private SkywalkingTracerExtractInterceptor extractInterceptor;

    private ConstructorInterceptor constructorInterceptor;

    private ActivateInterceptor activateInterceptor;

    @Before
    public void setUp() {
        spanBuilder = new SkywalkingSpanBuilder("test").withTag(Tags.COMPONENT.getKey(), "test");
        constructorWithSpanBuilderInterceptor = new ConstructorWithSpanBuilderInterceptor();
        spanLogInterceptor = new SpanLogInterceptor();
        logArgument = new Object[] {111111111L, event};
        logArgumentType = new Class[] {long.class, HashMap.class};

        setOperationNameInterceptor = new SpanSetOperationNameInterceptor();
        setOperationNameArgument = new Object[] {"testOperationName"};
        setOperationNameArgumentType = new Class[] {String.class};

        spanFinishInterceptor = new SpanFinishInterceptor();

        injectInterceptor = new SkywalkingTracerInjectInterceptor();
        extractInterceptor = new SkywalkingTracerExtractInterceptor();

        constructorInterceptor = new ConstructorInterceptor();
        activateInterceptor = new ActivateInterceptor();
    }

    @Test
    public void testCreateLocalSpan() throws Throwable {
        startSpan();
        stopSpan();

        TraceSegment tracingSegment = assertTraceSemgnets();
        List<AbstractTracingSpan> spans = SegmentHelper.getSpans(tracingSegment);
        assertThat(spans.size(), is(1));
        assertThat(spans.get(0).isEntry(), is(false));
        assertThat(spans.get(0).isExit(), is(false));
    }

    @Test
    public void testCreateEntrySpan() throws Throwable {
        spanBuilder.withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER);
        startSpan();
        stopSpan();

        TraceSegment tracingSegment = assertTraceSemgnets();
        List<AbstractTracingSpan> spans = SegmentHelper.getSpans(tracingSegment);
        assertThat(spans.size(), is(1));
        assertSpanCommonsAttribute(spans.get(0));
        assertThat(spans.get(0).isEntry(), is(true));
    }

    @Test
    public void testCreateExitSpanWithoutPeer() throws Throwable {
        spanBuilder.withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT);
        startSpan();
        stopSpan();

        TraceSegment tracingSegment = assertTraceSemgnets();
        List<AbstractTracingSpan> spans = SegmentHelper.getSpans(tracingSegment);
        assertThat(spans.size(), is(1));
        assertSpanCommonsAttribute(spans.get(0));
        assertThat(spans.get(0).isEntry(), is(false));
        assertThat(spans.get(0).isExit(), is(false));
    }

    @Test
    public void testCreateExitSpanWithPeer() throws Throwable {
        spanBuilder.withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
            .withTag(Tags.PEER_HOST_IPV4.getKey(), "127.0.0.1").withTag(Tags.PEER_PORT.getKey(), "8080");
        startSpan();
        stopSpan();

        TraceSegment tracingSegment = assertTraceSemgnets();
        List<AbstractTracingSpan> spans = SegmentHelper.getSpans(tracingSegment);
        assertThat(spans.size(), is(1));
        assertSpanCommonsAttribute(spans.get(0));
        assertThat(spans.get(0).isEntry(), is(false));
        assertThat(spans.get(0).isExit(), is(true));
    }

    private TraceSegment assertTraceSemgnets() {
        List<TraceSegment> segments = storage.getTraceSegments();
        assertThat(segments.size(), is(1));

        return segments.get(0);
    }

    @Test
    public void testInject() throws Throwable {
        spanBuilder.withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
            .withTag(Tags.PEER_HOST_IPV4.getKey(), "127.0.0.1").withTag(Tags.PEER_PORT.getKey(), 8080);
        startSpan();

        String extractValue = (String)injectInterceptor.afterMethod(enhancedInstance, "extract",
            null, null, null);

        ContextCarrier contextCarrier = new ContextCarrier().deserialize(extractValue);
        assertTrue(contextCarrier.isValid());
        assertThat(contextCarrier.getPeerHost(), is("#127.0.0.1:8080"));
        assertThat(contextCarrier.getSpanId(), is(0));
        assertThat(contextCarrier.getEntryOperationName(), is("#testOperationName"));
        stopSpan();
    }

    @Test
    public void testExtractWithValidateContext() throws Throwable {
        spanBuilder.withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
            .withTag(Tags.PEER_HOST_IPV4.getKey(), "127.0.0.1").withTag(Tags.PEER_PORT.getKey(), 8080);
        startSpan();
        extractInterceptor.afterMethod(enhancedInstance, "extract",
            new Object[] {"#AQA*#AQA*4WcWe0tQNQA*|3|1|1|#127.0.0.1:8080|#/portal/|#/testEntrySpan|#AQA*#AQA*Et0We0tQNQA*"}, new Class[] {String.class}, null);
        stopSpan();

        TraceSegment tracingSegment = assertTraceSemgnets();
        List<AbstractTracingSpan> spans = SegmentHelper.getSpans(tracingSegment);
        assertThat(tracingSegment.getRefs().size(), is(1));
        TraceSegmentRef ref = tracingSegment.getRefs().get(0);
        assertSegmentId(ref, "1.1.15006458883500001");
        assertSpanId(ref, 3);
        assertEntryApplicationInstanceId(ref, 1);
        assertPeerHost(ref, "127.0.0.1:8080");
        assertThat(spans.size(), is(1));
        assertSpanCommonsAttribute(spans.get(0));
    }
    @Test
    public void testExtractWithInValidateContext() throws Throwable {
        spanBuilder.withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
            .withTag(Tags.PEER_HOST_IPV4.getKey(), "127.0.0.1").withTag(Tags.PEER_PORT.getKey(), 8080);
        startSpan();
        extractInterceptor.afterMethod(enhancedInstance, "extract",
            new Object[] {"#AQA*#AQA*4WcWe0tQNQA*|3|#192.168.1.8:18002|#/portal/|#/testEntrySpan|#AQA*#AQA*Et0We0tQNQA*"}, new Class[] {String.class}, null);
        stopSpan();

        TraceSegment tracingSegment = assertTraceSemgnets();
        List<AbstractTracingSpan> spans = SegmentHelper.getSpans(tracingSegment);
        assertNull(tracingSegment.getRefs());
        assertSpanCommonsAttribute(spans.get(0));
    }

    @Test
    public void testContinuation() throws Throwable {
        startSpan();
        final MockEnhancedInstance continuationHolder = new MockEnhancedInstance();
        constructorInterceptor.onConstruct(continuationHolder, null);
        assertTrue(continuationHolder.getSkyWalkingDynamicField() instanceof ContextSnapshot);
        new Thread() {
            @Override public void run() {
                MockEnhancedInstance enhancedInstance = new MockEnhancedInstance();
                try {
                    startSpan(enhancedInstance);
                    activateInterceptor.afterMethod(continuationHolder, "activate", null, null, null);
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                } finally {
                    try {
                        stopSpan(enhancedInstance);
                    } catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                }
            }
        }.start();
        Thread.sleep(1000L);
        stopSpan();

        List<TraceSegment> segments = storage.getTraceSegments();
        assertThat(segments.size(), is(2));
        TraceSegment traceSegment = segments.get(0);
        assertThat(traceSegment.getRefs().size(), is(1));

        traceSegment = segments.get(1);
        assertNull(traceSegment.getRefs());
    }

    private void assertSpanCommonsAttribute(AbstractTracingSpan span) {
        assertThat(span.getOperationName(), is("testOperationName"));
        assertComponent(span, "test");
        assertLogSize(span, 1);
    }

    private void stopSpan() throws Throwable {
        stopSpan(enhancedInstance);
    }

    private void stopSpan(EnhancedInstance enhancedInstance) throws Throwable {
        spanFinishInterceptor.afterMethod(enhancedInstance, "finish", null, null, null);
    }

    private void startSpan() throws Throwable {
        startSpan(enhancedInstance);
    }

    private void startSpan(MockEnhancedInstance enhancedInstance) throws Throwable {
        constructorWithSpanBuilderInterceptor.onConstruct(enhancedInstance, new Object[] {spanBuilder});
        spanLogInterceptor.afterMethod(enhancedInstance, "log", logArgument, logArgumentType, null);

        setOperationNameInterceptor.afterMethod(enhancedInstance, "setOperationName",
            setOperationNameArgument, setOperationNameArgumentType, null);
    }

    private class MockEnhancedInstance implements EnhancedInstance {
        public Object object;

        @Override public Object getSkyWalkingDynamicField() {
            return object;
        }

        @Override public void setSkyWalkingDynamicField(Object value) {
            this.object = value;
        }
    }

    private class MockContinuationThread extends Thread {
        @Override
        public void run() {
            super.run();
        }
    }
}