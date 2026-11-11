package org.mvel2.optimizers.dynamic;

import junit.framework.TestCase;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;
import org.mvel2.compiler.Accessor;
import org.mvel2.integration.VariableResolverFactory;
import org.mvel2.integration.impl.MapVariableResolverFactory;
import org.mvel2.optimizers.AccessorOptimizer;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.impl.asm.ASMAccessorOptimizer;
import org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DynamicCollectionAccessorTest extends TestCase {
  private String previousOptimizer;
  private int previousThreshold;
  private long previousTimeSpan;

  protected void setUp() {
    AccessorOptimizer previous = OptimizerFactory.getDefaultAccessorCompiler();
    previousOptimizer = previous instanceof ASMAccessorOptimizer ? "ASM"
        : previous instanceof ReflectiveAccessorOptimizer ? OptimizerFactory.SAFE_REFLECTIVE : OptimizerFactory.DYNAMIC;
    previousThreshold = DynamicOptimizer.tenuringThreshold;
    previousTimeSpan = DynamicOptimizer.timeSpan;
    OptimizerFactory.setDefaultOptimizer(OptimizerFactory.DYNAMIC);
    DynamicOptimizer.tenuringThreshold = 0;
    DynamicOptimizer.timeSpan = 60000;
  }

  protected void tearDown() {
    DynamicOptimizer.tenuringThreshold = previousThreshold;
    DynamicOptimizer.timeSpan = previousTimeSpan;
    OptimizerFactory.setDefaultOptimizer(previousOptimizer);
  }

  public void testAsmLiteralAssignableToInterfaceArray() {
    OptimizerFactory.setDefaultOptimizer("ASM");
    Serializable compiled = MVEL.compileExpression("new java.io.Serializable[] {true, 'text', 1, null}");
    assertEquals(Boolean.TRUE, ((Object[]) MVEL.executeExpression(compiled))[0]);
    assertEquals("text", ((Object[]) MVEL.executeExpression(compiled))[1]);
    assertEquals(1, ((Object[]) MVEL.executeExpression(compiled))[2]);
    assertNull(((Object[]) MVEL.executeExpression(compiled))[3]);
  }

  public void testFailedOptimizationKeepsReflectiveAccessorWithoutRetry() {
    BlockingParserContext context = new BlockingParserContext();
    CountingAccessor safe = new CountingAccessor();
    // Boolean cannot be stored in Runnable[]; generation fails before evaluating anything.
    DynamicCollectionAccessor accessor = accessor(context, "true", Runnable[].class, safe);
    assertEquals("safe", ((Object[]) accessor.getValue(null, null, null))[0]);
    int accesses = context.configurationReads.get();
    assertTrue(accesses > 0);
    assertEquals("safe", ((Object[]) accessor.getValue(null, null, null))[0]);
    assertEquals(accesses, context.configurationReads.get());
    assertEquals(2, safe.calls.get());
  }

  public void testBusinessExceptionIsNotRetriedThroughReflection() {
    CountingAccessor safe = new CountingAccessor();
    ParserContext context = new ParserContext();
    context.addInput("counter", FailingCounter.class);
    DynamicCollectionAccessor accessor = accessor(context, "counter.fail()", Object[].class, safe);
    FailingCounter counter = new FailingCounter();
    Map<String, Object> variables = new HashMap<String, Object>();
    variables.put("counter", counter);
    try {
      accessor.getValue(null, null, new MapVariableResolverFactory(variables));
      fail("business exception expected");
    }
    catch (RuntimeException expected) {
      assertEquals(1, counter.calls.get());
      assertEquals(0, safe.calls.get());
    }
  }

  public void testConcurrentCallsUseReflectionWhileOneThreadOptimizes() throws Exception {
    BlockingParserContext context = new BlockingParserContext();
    CountingAccessor safe = new CountingAccessor();
    DynamicCollectionAccessor accessor = accessor(context, "true", Object[].class, safe);
    ExecutorService executor = Executors.newFixedThreadPool(9);
    context.blockNextRead.set(true);
    try {
      Future<Object> optimizing = executor.submit(() -> accessor.getValue(null, null, null));
      assertTrue(context.entered.await(10, TimeUnit.SECONDS));
      List<Future<Object>> concurrent = new ArrayList<Future<Object>>();
      for (int i = 0; i < 8; i++) {
        concurrent.add(executor.submit(() -> accessor.getValue(null, null, null)));
      }
      for (Future<Object> result : concurrent) {
        assertEquals("safe", ((Object[]) result.get(10, TimeUnit.SECONDS))[0]);
      }
      context.resume.countDown();
      assertEquals(Boolean.TRUE, ((Object[]) optimizing.get(10, TimeUnit.SECONDS))[0]);
      assertEquals(Boolean.TRUE, ((Object[]) accessor.getValue(null, null, null))[0]);
      assertEquals(8, safe.calls.get());
    }
    finally {
      context.resume.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  public void testDeoptimizationDiscardsInFlightCompilation() throws Exception {
    BlockingParserContext context = new BlockingParserContext();
    CountingAccessor safe = new CountingAccessor();
    DynamicCollectionAccessor accessor = accessor(context, "true", Object[].class, safe);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    context.blockNextRead.set(true);
    try {
      Future<Object> optimizing = executor.submit(() -> accessor.getValue(null, null, null));
      assertTrue(context.entered.await(10, TimeUnit.SECONDS));
      accessor.deoptimize();
      context.resume.countDown();
      assertEquals("safe", ((Object[]) optimizing.get(10, TimeUnit.SECONDS))[0]);
      assertEquals(Boolean.TRUE, ((Object[]) accessor.getValue(null, null, null))[0]);
      assertEquals(1, safe.calls.get());
    }
    finally {
      context.resume.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  public void testSharedCompiledArrayWithChangingValues() throws Exception {
    final Serializable compiled = MVEL.compileExpression("new java.lang.Object[] {$__.?flag, true}");
    ExecutorService executor = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<?>> tasks = new ArrayList<Future<?>>();
      for (int thread = 0; thread < 8; thread++) {
        tasks.add(executor.submit(() -> {
          await(start);
          Map<String, Object> data = new HashMap<String, Object>();
          Map<String, Object> variables = new HashMap<String, Object>();
          variables.put("$__", data);
          for (int i = 0; i < 1000; i++) {
            Object value = i % 3 == 0 ? null : i % 2 == 0;
            data.put("flag", value);
            Object[] result = (Object[]) MVEL.executeExpression(compiled, variables);
            assertEquals(value, result[0]);
            assertEquals(Boolean.TRUE, result[1]);
          }
        }));
      }
      start.countDown();
      for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
    }
    finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  private static DynamicCollectionAccessor accessor(ParserContext context, String element, Class type, Accessor safe) {
    return new DynamicCollectionAccessor(context, new Object[]{element}, type, new char[0], 0, 0,
        DynamicOptimizer.COLLECTION, safe);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static class BlockingParserContext extends ParserContext {
    final AtomicInteger configurationReads = new AtomicInteger();
    final AtomicBoolean blockNextRead = new AtomicBoolean();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);

    public ParserConfiguration getParserConfiguration() {
      configurationReads.incrementAndGet();
      if (blockNextRead.compareAndSet(true, false)) {
        entered.countDown();
        await(resume);
      }
      return super.getParserConfiguration();
    }
  }

  private static class CountingAccessor implements Accessor {
    final AtomicInteger calls = new AtomicInteger();

    public Object getValue(Object ctx, Object elCtx, VariableResolverFactory factory) {
      calls.incrementAndGet();
      return new Object[]{"safe"};
    }

    public Object setValue(Object ctx, Object elCtx, VariableResolverFactory factory, Object value) {
      throw new UnsupportedOperationException();
    }

    public Class getKnownEgressType() {
      return Object[].class;
    }
  }

  public static class FailingCounter {
    final AtomicInteger calls = new AtomicInteger();

    public Object fail() {
      calls.incrementAndGet();
      throw new IllegalStateException("business failure");
    }
  }
}
