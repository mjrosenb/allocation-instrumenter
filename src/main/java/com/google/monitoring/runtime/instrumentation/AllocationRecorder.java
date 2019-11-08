/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.monitoring.runtime.instrumentation;

import com.google.common.collect.ForwardingMap;
import com.google.common.collect.MapMaker;
import java.lang.instrument.Instrumentation;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/**
 * The logic for recording allocations, called from bytecode rewritten by {@link
 * AllocationInstrumenter}.
 */
public class AllocationRecorder {

  // See the comment above the addShutdownHook in the static block above
  // for why this is volatile.
  private static volatile Instrumentation instrumentation = null;

  static Instrumentation getInstrumentation() {
    return instrumentation;
  }
  
  static void setInstrumentation(Instrumentation inst) {
    instrumentation = inst;
  }
  static {
      System.out.println("initializing recordingAllocation...");
  }
  // Used for reentrancy checks
  // NOTE(mrosenbe): this used to be final, but it looked like there was some awful
  // condition where the allocation would get instrumented, and recordSample would get called
  // on *this* allocation, before recordingAllocation got set, and I think java removed
  // my null checks because how could a private static final value be null?
  // this all may have been related to me specifying the agent twice.  I didn't check
  // to see if this issue went away after I started specifying the agent once.
  private static ThreadLocal<Boolean> recordingAllocation = new ThreadLocal<Boolean>();
  // NOTE(mrosenbe): more of my debugging cruft 
  private static ThreadLocal<Long> allocationCount = new ThreadLocal<Long>();
  static {
      System.out.println("done, recordingAllocation" + recordingAllocation.toString());
  }    
  // Mostly because, yes, arrays are faster than collections.
  private static volatile Sampler[] additionalSamplers;

  // Protects mutations of additionalSamplers.  Reads are okay because
  // the field is volatile, so anyone who reads additionalSamplers
  // will get a consistent view of it.
  private static final Object samplerLock = new Object();

  // Stores the object sizes for the last ~100000 encountered classes
  private static final ForwardingMap<Class<?>, Long> classSizesMap =
      new ForwardingMap<Class<?>, Long>() {
        private final ConcurrentMap<Class<?>, Long> map = new MapMaker().weakKeys().makeMap();

        @Override
        public Map<Class<?>, Long> delegate() {
          return map;
        }

        // The approximate maximum size of the map
        private static final int MAX_SIZE = 100000;

        // The approximate current size of the map; since this is not an AtomicInteger
        // and since we do not synchronize the updates to this field, it will only be
        // an approximate size of the map; it's good enough for our purposes though,
        // and not synchronizing the updates saves us some time
        private int approximateSize = 0;

        @Override
        public Long put(Class<?> key, Long value) {
          // if we have too many elements, delete about 10% of them
          // this is expensive, but needs to be done to keep the map bounded
          // we also need to randomize the elements we delete: if we remove the same
          // elements all the time, we might end up adding them back to the map
          // immediately after, and then remove them again, then add them back, etc.
          // which will cause this expensive code to be executed too often
          if (approximateSize >= MAX_SIZE) {
            for (Iterator<Class<?>> it = keySet().iterator(); it.hasNext(); ) {
              it.next();
              if (Math.random() < 0.1) {
                it.remove();
              }
            }

            // get the exact size; another expensive call, but we need to correct
            // approximateSize every once in a while, or the difference between
            // approximateSize and the actual size might become significant over time;
            // the other solution is synchronizing every time we update approximateSize,
            // which seems even more expensive
            approximateSize = size();
          }

          approximateSize++;
          return super.put(key, value);
        }
      };

  /**
   * Adds a {@link Sampler} that will get run <b>every time an allocation is performed from Java
   * code</b>. Use this with <b>extreme</b> judiciousness!
   *
   * @param sampler The sampler to add.
   */
  public static void addSampler(Sampler sampler) {
      // NOTE(mrosenbe): I added this in as a bit of a debug step.  If you don't use this code, then
      // you'll need to remove the call to addSampler(null)
      if (sampler == null) {
          System.out.println("add with null, treating as a debug...");
          System.out.println(String.format("%s", recordingAllocation.get()));
          return;
      }
    System.out.println(String.format("calling addSampler with: %s", sampler));
    synchronized (samplerLock) {
      Sampler[] samplers = additionalSamplers;
      System.out.println(String.format("Write: samplers.id=%d", System.identityHashCode(additionalSamplers)));
      System.out.println(String.format("Write: class.id=%d", System.identityHashCode(AllocationRecorder.class)));       
      /* create a new list of samplers from the old, adding this sampler */
      if (samplers != null) {
          System.out.println("samplers was not null, reallocing");
        Sampler[] newSamplers = new Sampler[samplers.length + 1];
        System.arraycopy(samplers, 0, newSamplers, 0, samplers.length);
        newSamplers[samplers.length] = sampler;
        additionalSamplers = newSamplers;
      } else {
        System.out.println("samplers was null. allocating fresh");          
        Sampler[] newSamplers = new Sampler[1];
        newSamplers[0] = sampler;
        additionalSamplers = newSamplers;
      }
    }
    System.out.println(String.format("Write: samplers.id=%d", System.identityHashCode(additionalSamplers)));    
    System.out.println("done calling addSampler");
  }

  /**
   * Removes the given {@link Sampler}.
   *
   * @param sampler The sampler to remove.
   */
  public static void removeSampler(Sampler sampler) {
    synchronized (samplerLock) {
      Sampler[] samplers = additionalSamplers;
      int samplerCount = samplers.length;
      for (Sampler s : samplers) {
        if (s.equals(sampler)) {
          samplerCount--;
        }
      }
      Sampler[] newSamplers = new Sampler[samplerCount];
      int i = 0;
      for (Sampler s : samplers) {
        if (!s.equals(sampler)) {
          newSamplers[i++] = s;
        }
      }
      additionalSamplers = newSamplers;
    }
  }

  /**
   * Returns the size of the given object. If the object is not an array, we check the cache first,
   * and update it as necessary.
   *
   * @param obj the object.
   * @param isArray indicates if the given object is an array.
   * @param instr the instrumentation object to use for finding the object size
   * @return the size of the given object.
   */
  private static long getObjectSize(Object obj, boolean isArray, Instrumentation instr) {
    if (isArray) {
      return instr.getObjectSize(obj);
    }

    Class<?> clazz = obj.getClass();
    Long classSize = classSizesMap.get(clazz);
    if (classSize == null) {
      classSize = instr.getObjectSize(obj);
      classSizesMap.put(clazz, classSize);
    }

    return classSize;
  }

  public static void recordAllocation(Class<?> cls, Object newObj) {
    // The use of replace makes calls to this method relatively ridiculously
    // expensive. (so I removed it?)
      String typename = cls.getName();// .replace('.', '/');
    recordAllocation(-1, typename, newObj);
  }

  /**
   * Records the allocation. This method is invoked on every allocation performed by the system.
   *
   * @param count the count of how many instances are being allocated, if an array is being
   *     allocated. If an array is not being allocated, then this value will be -1.
   * @param desc the descriptor of the class/primitive type being allocated.
   * @param newObj the new <code>Object</code> whose allocation is being recorded.
   */
  public static void recordAllocation(int count, String desc, Object newObj) {
    if (recordingAllocation == null || Objects.equals(recordingAllocation.get(), Boolean.TRUE)) {
      return;
    }

    recordingAllocation.set(Boolean.TRUE);
    /*
    if (count >= 0) {
      desc = desc.replace('.', '/');
    }
    */
    // NOTE(mrosenbe): it is safe to add printing logic here because of the reentrancy checks above.
    // if don't try to  add any logic outside of the reentrancy check, this will get caught in an infinite loop.
    /*
    if (allocationCount != null) {
        if (allocationCount.get() == null) {
            allocationCount.set(0L);
        }
        long locCount = allocationCount.get() + 1;
        allocationCount.set(locCount);
        if ((locCount & 0xfff) == 0) {
            if (additionalSamplers != null) {
                System.out.println(String.format("A: (%s, %s)", instrumentation, additionalSamplers.length));
            } else {
                System.out.println(String.format("A: (%s, NULL)", instrumentation));
            }
            System.out.println(String.format("Use: samplers.id=%d", System.identityHashCode(additionalSamplers)));
            System.out.println(String.format("Use: class.id=%d", System.identityHashCode(AllocationRecorder.class))); 
        }
        
    }
    */
    // Copy value into local variable to prevent NPE that occurs when
    // instrumentation field is set to null by this class's shutdown hook
    // after another thread passed the null check but has yet to call
    // instrumentation.getObjectSize()
    // See https://github.com/google/allocation-instrumenter/issues/15
    Instrumentation instr = instrumentation;
    if (instr != null) {
      // calling getObjectSize() could be expensive,
      // so make sure we do it only once per object
      long objectSize = -1;

      Sampler[] samplers = additionalSamplers;
      if (samplers != null) {
        if (objectSize < 0) {
          objectSize = getObjectSize(newObj, (count >= 0), instr);
        }
        //System.out.println(String.format("recording allocation.  There are %d samplers", samplers.length));
        for (Sampler sampler : samplers) {
          sampler.sampleAllocation(count, desc, newObj, objectSize);
        }
      }
    }

    recordingAllocation.set(Boolean.FALSE);
  }
}
