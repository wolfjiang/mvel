package org.mvel2.tests.core;

import junit.framework.TestCase;
import org.mvel2.DataConversion;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class DataConversionConcurrencyTest extends TestCase {
  public void testConcurrentArrayRegistrationPreservesConverters() throws Exception {
    Class[] bases = {String.class, Object.class, Integer.class, Boolean.class,
        Long.class, Double.class, Byte.class, Character.class};
    final List<Class> types = new ArrayList<Class>();
    for (Class base : bases) {
      for (int dimensions = 2; dimensions <= 100; dimensions++) {
        types.add(Array.newInstance(base, new int[dimensions]).getClass());
      }
    }
    ExecutorService executor = Executors.newFixedThreadPool(9);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<?>> tasks = new ArrayList<Future<?>>();
      for (int thread = 0; thread < 8; thread++) {
        final int index = thread;
        tasks.add(executor.submit(() -> {
          await(start);
          for (int i = index; i < types.size(); i += 8) {
            Object result = DataConversion.convert(Collections.emptyList(), types.get(i));
            assertEquals(types.get(i), result.getClass());
            assertEquals(0, Array.getLength(result));
          }
        }));
      }
      tasks.add(executor.submit(() -> {
        await(start);
        for (int i = 0; i < 10000; i++) {
          assertTrue(DataConversion.canConvert(Boolean.class, Object.class));
          assertTrue(DataConversion.canConvert(String.class, Object.class));
        }
      }));
      start.countDown();
      for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
      for (Class type : types) assertTrue(DataConversion.canConvert(type, List.class));
      assertTrue(DataConversion.canConvert(Boolean.class, Object.class));
    }
    finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
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
}
