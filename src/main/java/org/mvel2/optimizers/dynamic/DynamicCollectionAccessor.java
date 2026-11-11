/**
 * MVEL 2.0
 * Copyright (C) 2007 The Codehaus
 * Mike Brock, Dhanji Prasanna, John Graham, Mark Proctor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mvel2.optimizers.dynamic;

import org.mvel2.CompileException;
import org.mvel2.OptimizationFailure;
import org.mvel2.ParserContext;
import org.mvel2.compiler.Accessor;
import org.mvel2.integration.VariableResolverFactory;
import org.mvel2.optimizers.OptimizationNotSupported;
import org.mvel2.optimizers.OptimizerFactory;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static java.lang.System.currentTimeMillis;

public class DynamicCollectionAccessor implements DynamicAccessor {
  private ParserContext pCtx;
  private Object rootObject;
  private Class colType;

  private char[] property;
  private int start;
  private int offset;

  private volatile long stamp;
  private int type;

  // Approximate hotness only; lost increments do not affect evaluation correctness.
  private volatile int runcount;

  private final Accessor _safeAccessor;
  private volatile AccessorState state;
  private static final AtomicReferenceFieldUpdater<DynamicCollectionAccessor, AccessorState> STATE_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(DynamicCollectionAccessor.class, AccessorState.class, "state");

  private static final class AccessorState {
    private final Accessor accessor;
    private final boolean optimizationAttempted;

    private AccessorState(Accessor accessor, boolean optimizationAttempted) {
      this.accessor = accessor;
      this.optimizationAttempted = optimizationAttempted;
    }
  }

  public DynamicCollectionAccessor(ParserContext pCtx, Object rootObject, Class colType, char[] property, int start, int offset, int type, Accessor _accessor) {
    this.pCtx = pCtx;
    this.rootObject = rootObject;
    this.colType = colType;
    this._safeAccessor = _accessor;
    this.state = new AccessorState(_accessor, false);
    this.type = type;

    this.property = property;
    this.start = start;
    this.offset = offset;

    stamp = currentTimeMillis();
  }

  public Object getValue(Object ctx, Object elCtx, VariableResolverFactory variableFactory) {
    AccessorState current = state;
    if (!current.optimizationAttempted) {
      if (++runcount > DynamicOptimizer.tenuringThreshold) {
        if ((currentTimeMillis() - stamp) < DynamicOptimizer.timeSpan) {
          AccessorState claimed = new AccessorState(current.accessor, true);
          if (STATE_UPDATER.compareAndSet(this, current, claimed)) {
            optimize(claimed, ctx, elCtx, variableFactory);
          }
          current = state;
        }
        else {
          runcount = 0;
          stamp = currentTimeMillis();
        }
      }
    }

    // Evaluate outside the optimization catch: business failures must not be retried.
    return current.accessor.getValue(ctx, elCtx, variableFactory);
  }

  public Object setValue(Object ctx, Object elCtx, VariableResolverFactory variableFactory, Object value) {
    runcount++;
    return state.accessor.setValue(ctx, elCtx, variableFactory, value);
  }

  private void optimize(AccessorState claimed, Object ctx, Object elCtx, VariableResolverFactory variableResolverFactory) {

    if (DynamicOptimizer.isOverloaded()) {
      DynamicOptimizer.enforceTenureLimit();
    }

    try {
      Accessor optimized = OptimizerFactory.getAccessorCompiler("ASM").optimizeCollection(pCtx, rootObject, colType,
          property, start, offset, ctx, elCtx, variableResolverFactory);
      // A concurrent deoptimization invalidates this attempt, without taking a hot-path lock.
      STATE_UPDATER.compareAndSet(this, claimed, new AccessorState(optimized, true));
    }
    catch (CompileException | OptimizationFailure | OptimizationNotSupported e) {
      // Keep the reflective accessor and do not retry until explicitly deoptimized.
    }
  }


  public void deoptimize() {
    runcount = 0;
    stamp = currentTimeMillis();
    state = new AccessorState(_safeAccessor, false);
  }

  public long getStamp() {
    return stamp;
  }

  public int getRuncount() {
    return runcount;
  }

  public Class getKnownEgressType() {
    return colType;
  }
}
