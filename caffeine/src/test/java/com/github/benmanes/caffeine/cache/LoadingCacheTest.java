/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.cache.RemovalCause.SIZE;
import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.CollectionSubject.assertThat;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.IntSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static uk.org.lidalia.slf4jext.ConventionalLevelHierarchy.INFO_LEVELS;
import static uk.org.lidalia.slf4jext.Level.WARN;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.testing.Int;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;

/**
 * The test cases for the {@link LoadingCache} interface that simulate the most generic usages.
 * These tests do not validate eviction management, concurrency behavior, or the
 * {@link Cache#asMap()} view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@SuppressWarnings("FutureReturnValueIgnored")
@Test(dataProviderClass = CacheProvider.class)
public final class LoadingCacheTest {

  /* --------------- get --------------- */

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_null(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.get(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.NULL)
  public void get_absent_null(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey())).isNull();
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @CacheSpec(loader = Loader.EXCEPTIONAL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void get_absent_failure(LoadingCache<Int, Int> cache, CacheContext context) {
    try {
      cache.get(context.absentKey());
    } finally {
      assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
    }
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    assertThat(cache.get(key)).isEqualTo(key.negate());
    assertThat(context).stats().hits(0).misses(1).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.get(context.firstKey())).isEqualTo(context.firstKey().negate());
    assertThat(cache.get(context.middleKey())).isEqualTo(context.middleKey().negate());
    assertThat(cache.get(context.lastKey())).isEqualTo(context.lastKey().negate());
    assertThat(context).stats().hits(3).misses(0).success(0).failures(0);
  }

  /* --------------- getAll --------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAll_iterable_null(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.getAll(null);
  }

  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAll_iterable_nullKey(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.getAll(Collections.singletonList(null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_iterable_empty(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAll(List.of())).isExhaustivelyEmpty();
    assertThat(context).stats().hits(0).misses(0);
  }

  @CacheSpec(loader = Loader.BULK_MODIFY_KEYS)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAll_immutable_keys(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.getAll(context.absentKeys());
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAll_immutable_result(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.getAll(context.absentKeys()).clear();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.NULL)
  public void getAll_absent_null(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAll(context.absentKeys())).isExhaustivelyEmpty();
  }

  @CacheSpec(loader = Loader.BULK_NULL)
  @Test(dataProvider = "caches", expectedExceptions = Exception.class)
  public void getAll_absent_bulkNull(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.getAll(context.absentKeys());
  }

  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_EXCEPTIONAL })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void getAll_absent_failure(LoadingCache<Int, Int> cache, CacheContext context) {
    try {
      cache.getAll(context.absentKeys());
    } finally {
      int misses = context.absentKeys().size();
      int loadFailures = (context.loader().isBulk() || context.isSync()) ? 1 : misses;
      assertThat(context).stats().hits(0).misses(misses).success(0).failures(loadFailures);
    }
  }

  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_EXCEPTIONAL })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void getAll_absent_failure_iterable(LoadingCache<Int, Int> cache, CacheContext context) {
    try {
      cache.getAll(() -> context.absentKeys().iterator());
    } finally {
      int misses = context.absentKeys().size();
      int loadFailures = (context.loader().isBulk() || context.isSync()) ? 1 : misses;
      assertThat(context).stats().hits(0).misses(misses).success(0).failures(loadFailures);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys());

    int count = context.absentKeys().size();
    int loads = context.loader().isBulk() ? 1 : count;
    assertThat(result).hasSize(count);
    assertThat(context).stats().hits(0).misses(count).success(loads).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_partial(LoadingCache<Int, Int> cache, CacheContext context) {
    var expect = new HashMap<Int, Int>();
    expect.put(context.firstKey(), context.firstKey().negate());
    expect.put(context.middleKey(), context.middleKey().negate());
    expect.put(context.lastKey(), context.lastKey().negate());
    var result = cache.getAll(expect.keySet());

    assertThat(result).containsExactlyEntriesIn(expect);
    assertThat(context).stats().hits(expect.size()).misses(0).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_full(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.original().keySet());
    assertThat(result).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(result.size()).misses(0).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.BULK_NEGATIVE_EXCEEDS },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_exceeds(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys());

    assertThat(result.keySet()).containsExactlyElementsIn(context.absentKeys());
    assertThat(cache).hasSizeGreaterThan(context.initialSize() + context.absentKeys().size());
    assertThat(context).stats().hits(0).misses(result.size()).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.BULK_DIFFERENT,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_different(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys());

    assertThat(result).isEmpty();
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(context).stats().hits(0).misses(context.absent().size()).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_duplicates(LoadingCache<Int, Int> cache, CacheContext context) {
    var absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    var keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    var result = cache.getAll(keys);
    assertThat(result).containsExactlyKeys(keys);

    int loads = context.loader().isBulk() ? 1 : absentKeys.size();
    assertThat(context).stats().hits(context.initialSize())
        .misses(absentKeys.size()).success(loads).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<Int>(context.absentKeys());
    Collections.shuffle(keys);

    assertThat(cache.getAll(keys).keySet()).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_partial(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    assertThat(cache.getAll(keys).keySet()).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_present(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    assertThat(cache.getAll(keys).keySet()).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_NEGATIVE_EXCEEDS,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_exceeds(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = new ArrayList<>(cache.getAll(keys).keySet());
    assertThat(result.subList(0, keys.size())).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void getAll_jdk8186171(CacheContext context) {
    @SuppressWarnings("HashCodeToString")
    class Key {
      @Override public int hashCode() {
        return 0; // to put keys in one bucket
      }
    }
    LoadingCache<Object, Int> cache = context.build(key -> null);

    var keys = intern(new ArrayList<Key>());
    for (int i = 0; i < Population.FULL.size(); i++) {
      keys.add(new Key());
    }
    Key key = Iterables.getLast(keys);
    Int value = context.absentValue();
    cache.put(key, value);

    var result = cache.getAll(keys);
    assertThat(result).containsExactly(key, value);
    assertThat(result.values()).doesNotContain(null);
  }

  /* --------------- refresh --------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void refresh_null(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.refresh(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_INCOMPLETE, implementation = Implementation.Caffeine)
  public void refresh_dedupe(LoadingCache<Int, Int> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    var future1 = cache.refresh(key);
    var future2 = cache.refresh(key);
    assertThat(future1).isSameInstanceAs(future2);

    future1.complete(context.absentValue());
    assertThat(cache).containsEntry(key, context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.NULL,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_remove(LoadingCache<Int, Int> cache, CacheContext context) {
    var future = cache.refresh(context.firstKey());
    assertThat(future).succeedsWithNull();
    assertThat(cache).hasSize(context.initialSize() - 1);
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(1).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      loader = Loader.NULL, population = Population.EMPTY)
  public void refresh_ignored(LoadingCache<Int, Int> cache, CacheContext context) {
    var future = cache.refresh(context.absentKey());
    assertThat(future).succeedsWithNull();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      loader = Loader.EXCEPTIONAL, removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void refresh_failure(LoadingCache<Int, Int> cache, CacheContext context) {
    // Shouldn't leak exception to caller nor retain the future; should retain the stale entry
    var future1 = cache.refresh(context.absentKey());
    var future2 = cache.refresh(context.firstKey());
    var future3 = cache.refresh(context.lastKey());
    assertThat(future2).isNotSameInstanceAs(future3);
    assertThat(future1).hasCompletedExceptionally();
    assertThat(future2).hasCompletedExceptionally();
    assertThat(future3).hasCompletedExceptionally();
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).stats().success(0).failures(3);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_INCOMPLETE, implementation = Implementation.Caffeine,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void refresh_cancel(LoadingCache<Int, Int> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    var future1 = cache.refresh(key);
    assertThat(future1).isNotDone();
    future1.cancel(true);

    var future2 = cache.refresh(key);
    assertThat(future1).isNotSameInstanceAs(future2);

    future2.cancel(false);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @CacheSpec(loader = Loader.NULL)
  @Test(dataProvider = "caches")
  public void refresh_absent_null(LoadingCache<Int, Int> cache, CacheContext context) {
    var future = cache.refresh(context.absentKey());
    assertThat(future).succeedsWithNull();
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(
      maximumSize = Maximum.UNREACHABLE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING }, population = Population.SINGLETON)
  public void refresh_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var future = cache.refresh(key);
    assertThat(future).succeedsWith(key.negate());
    assertThat(cache).hasSize(1 + context.initialSize());
    assertThat(cache).containsEntry(context.absentKey(), key.negate());
    assertThat(context).stats().hits(0).misses(0).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.NULL,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_null(LoadingCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var future = cache.refresh(key);
      assertThat(future).succeedsWithNull();
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(0).failures(count);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).doesNotContainKey(key);
    }
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_sameValue(LoadingCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var future = cache.refresh(key);
      assertThat(future).succeedsWith(context.original().get(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).containsEntry(key, context.original().get(key));
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, loader = Loader.IDENTITY)
  public void refresh_present_sameInstance(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentKey());
    var future = cache.refresh(context.absentKey());

    assertThat(cache).hasSize(1);
    assertThat(future).succeedsWith(context.absentKey());
    assertThat(context).removalNotifications().isEmpty();
    assertThat(context).stats().hits(0).misses(0).success(1).failures(0);
    assertThat(cache).containsEntry(context.absentKey(), context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.IDENTITY,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_differentValue(LoadingCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var future = cache.refresh(key);
      assertThat(future).succeedsWith(key);
      assertThat(cache).containsEntry(key, key);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void refresh_conflict(CacheContext context) {
    var refresh = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int updated = Int.valueOf(2);
    Int refreshed = Int.valueOf(3);
    LoadingCache<Int, Int> cache = context.build(k -> {
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    var future = cache.refresh(key);
    assertThat(cache.asMap().put(key, updated)).isEqualTo(original);

    refresh.set(true);
    if (context.isGuava()) {
      future.join();
    } else {
      assertThat(future).succeedsWith(refreshed);
    }

    await().untilAsserted(() -> assertThat(context).removalNotifications().hasSize(2));
    assertThat(cache).containsEntry(key, updated);
    assertThat(context).removalNotifications().containsExactlyValues(original, refreshed);

    assertThat(context).stats().success(1).failures(0);
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(2).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, executor = CacheExecutor.THREADED,
      removalListener = Listener.CONSUMING)
  public void refresh_put(CacheContext context) {
    var started = new AtomicBoolean();
    var refresh = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    Int updated = Int.valueOf(3);
    LoadingCache<Int, Int> cache = context.build(k -> {
      started.set(true);
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    assertThat(started.get()).isFalse();

    var future = cache.refresh(key);
    await().untilTrue(started);
    cache.put(key, updated);
    refresh.set(true);

    if (context.isGuava()) {
      future.join();
    } else {
      assertThat(future).succeedsWith(refreshed);
    }

    await().untilAsserted(() -> {
      assertThat(context).removalNotifications().withCause(REPLACED).contains(key, refreshed);
    });

    assertThat(context).stats().success(1).failures(0);
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(2).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, executor = CacheExecutor.THREADED,
      removalListener = Listener.CONSUMING)
  public void refresh_invalidate(CacheContext context) {
    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    LoadingCache<Int, Int> cache = context.build(k -> {
      started.set(true);
      await().untilTrue(done);
      return refreshed;
    });

    cache.put(key, original);
    var future = cache.refresh(key);
    await().untilTrue(started);

    cache.invalidate(key);
    done.set(true);

    if (context.isGuava()) {
      future.join();
    } else {
      assertThat(future).succeedsWith(refreshed);
    }

    if (context.isGuava()) {
      await().untilAsserted(() -> assertThat(cache).containsEntry(key, refreshed));
      assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(1).exclusively();
    } else {
      // linearizable
      await().untilAsserted(() -> assertThat(cache).doesNotContainKey(key));
      assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(2).exclusively();
    }
    assertThat(context).stats().success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, executor = CacheExecutor.THREADED,
      expireAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING)
  public void refresh_expired(CacheContext context) {
    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    LoadingCache<Int, Int> cache = context.build(k -> {
      started.set(true);
      await().untilTrue(done);
      return refreshed;
    });

    cache.put(key, original);
    var future = cache.refresh(key);

    await().untilTrue(started);
    context.ticker().advance(10, TimeUnit.MINUTES);
    assertThat(cache).doesNotContainKey(key);

    done.set(true);
    if (context.isGuava()) {
      future.join();
    } else {
      assertThat(future).succeedsWith(refreshed);
    }

    if (context.isGuava()) {
      await().untilAsserted(() -> assertThat(cache).containsEntry(key, refreshed));
      assertThat(context).removalNotifications().withCause(EXPIRED).hasSize(1).exclusively();
    } else {
      // linearizable
      await().untilAsserted(() -> assertThat(cache).doesNotContainKey(key));
      assertThat(context).removalNotifications().withCause(EXPIRED).hasSize(1);
      assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(1);
    }
    assertThat(context).stats().success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, executor = CacheExecutor.THREADED,
      maximumSize = Maximum.ONE, weigher = CacheWeigher.DEFAULT,
      removalListener = Listener.CONSUMING)
  public void refresh_evicted(CacheContext context) {
    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    Int key1 = context.absentKey();
    Int key2 = key1.add(1);
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    LoadingCache<Int, Int> cache = context.build(k -> {
      started.set(true);
      await().forever().untilTrue(done);
      return refreshed;
    });

    cache.put(key1, original);
    var future = cache.refresh(key1);

    await().untilTrue(started);
    cache.put(key2, original);
    cache.cleanUp();
    assertThat(cache).doesNotContainKey(key1);

    done.set(true);
    if (context.isGuava()) {
      future.join();
    } else {
      assertThat(future).succeedsWith(refreshed);
    }

    if (context.isGuava()) {
      await().untilAsserted(() -> assertThat(cache).containsEntry(key1, refreshed));
      await().untilAsserted(() -> assertThat(cache).doesNotContainKey(key2));
      assertThat(context).removalNotifications().withCause(SIZE).hasSize(2).exclusively();
    } else {
      // linearizable
      await().untilAsserted(() -> assertThat(cache).doesNotContainKey(key1));
      await().untilAsserted(() -> assertThat(cache).containsEntry(key2, original));
      assertThat(context).removalNotifications().withCause(SIZE).hasSize(1);
      assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(1);
    }
    assertThat(context).stats().success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void refresh_cancel_noLog(CacheContext context) {
    var cacheLoader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        var future = new CompletableFuture<Int>();
        future.cancel(false);
        return future;
      }
    };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    TestLoggerFactory.getAllTestLoggers().values()
        .forEach(logger -> logger.setEnabledLevels(INFO_LEVELS));

    cache.refresh(context.absentKey());
    assertThat(TestLoggerFactory.getLoggingEvents()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void refresh_timeout_noLog(CacheContext context) {
    var cacheLoader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        var future = new CompletableFuture<Int>();
        future.orTimeout(0, TimeUnit.SECONDS);
        await().until(() -> future.isDone());
        return future;
      }
    };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    TestLoggerFactory.getAllTestLoggers().values()
        .forEach(logger -> logger.setEnabledLevels(INFO_LEVELS));

    cache.refresh(context.absentKey());
    assertThat(TestLoggerFactory.getLoggingEvents()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void refresh_error_log(CacheContext context) throws Exception {
    var expected = new RuntimeException();
    CacheLoader<Int, Int> cacheLoader = key -> { throw expected; };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    TestLoggerFactory.getAllTestLoggers().values()
        .forEach(logger -> logger.setEnabledLevels(INFO_LEVELS));

    cache.refresh(context.absentKey());
    var event = Iterables.getOnlyElement(TestLoggerFactory.getLoggingEvents());
    assertThat(event.getThrowable().orElseThrow()).hasCauseThat().isSameInstanceAs(expected);
    assertThat(event.getLevel()).isEqualTo(WARN);
  }

  /* --------------- refreshAll --------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void refreshAll_null(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.refreshAll(null);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void refreshAll_nullKey(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.refreshAll(Collections.singletonList(null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void refreshAll_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.refreshAll(context.absentKeys()).join();
    int count = context.absentKeys().size();
    assertThat(result).hasSize(count);
    assertThat(cache).hasSize(context.initialSize() + count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, loader = Loader.IDENTITY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void refreshAll_present(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.refreshAll(context.original().keySet()).join();
    int count = context.original().keySet().size();
    assertThat(result).hasSize(count);

    var expected = context.original().keySet().stream().collect(toMap(identity(), identity()));
    assertThat(cache).containsExactlyEntriesIn(expected);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      loader = Loader.EXCEPTIONAL, removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void refreshAll_failure(LoadingCache<Int, Int> cache, CacheContext context) {
    var future = cache.refreshAll(List.of(
        context.absentKey(), context.firstKey(), context.lastKey()));
    assertThat(future).hasCompletedExceptionally();
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_INCOMPLETE, implementation = Implementation.Caffeine,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void refreshAll_cancel(LoadingCache<Int, Int> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    var future1 = cache.refresh(key);
    var future2 = cache.refreshAll(List.of(key));

    assertThat(future1).isNotDone();
    future1.cancel(true);

    assertThat(future2).hasCompletedExceptionally();
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  /* --------------- CacheLoader --------------- */

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void loadAll() throws Exception {
    CacheLoader<Object, ?> loader = key -> key;
    loader.loadAll(Set.of());
  }

  @Test
  public void reload() throws Exception {
    CacheLoader<Int, Int> loader = key -> key;
    assertThat(loader.reload(Int.valueOf(1), Int.valueOf(1))).isEqualTo(1);
  }

  @Test
  public void asyncLoad_exception() throws Exception {
    var e = new Exception();
    CacheLoader<Int, Int> loader = key -> { throw e; };
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run))
        .failsWith(CompletionException.class).hasCauseThat().isSameInstanceAs(e);
  }

  @Test
  public void asyncLoad() {
    CacheLoader<Int, Int> loader = key -> key;
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run)).succeedsWith(1);
  }

  @Test
  public void asyncLoadAll_exception() throws Exception {
    var e = new Exception();
    var loader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) throws Exception {
        throw new AssertionError();
      }
      @Override public Map<Int, Int> loadAll(Set<? extends Int> keys) throws Exception {
        throw e;
      }
    };
    assertThat(loader.asyncLoadAll(Int.setOf(1), Runnable::run))
        .failsWith(CompletionException.class).hasCauseThat().isSameInstanceAs(e);
  }

  @Test
  public void asyncLoadAll() throws Throwable {
    CacheLoader<Object, ?> loader = key -> key;
    assertThat(loader.asyncLoadAll(Set.of(), Runnable::run))
        .failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void asyncReload_exception() throws Exception {
    for (var e : List.of(new Exception(), new RuntimeException())) {
      CacheLoader<Int, Int> loader = key -> { throw e; };
      assertThat(loader.asyncReload(Int.valueOf(1), Int.valueOf(1), Runnable::run))
          .failsWith(CompletionException.class).hasCauseThat().isSameInstanceAs(e);
    }
  }

  @Test
  public void asyncReload() throws Exception {
    CacheLoader<Int, Int> loader = Int::negate;
    var future = loader.asyncReload(Int.valueOf(1), Int.valueOf(2), Runnable::run);
    assertThat(future).succeedsWith(-1);
  }

  @SuppressWarnings("CheckReturnValue")
  @Test(expectedExceptions = NullPointerException.class)
  public void bulk_null() {
    CacheLoader.bulk(null);
  }

  @Test
  public void bulk_absent() throws Exception {
    CacheLoader<Int, Int> loader = CacheLoader.bulk(keys -> Map.of());
    assertThat(loader.loadAll(Int.setOf(1))).isEmpty();
    assertThat(loader.load(Int.valueOf(1))).isNull();
  }

  @Test
  public void bulk_present() throws Exception {
    CacheLoader<Int, Int> loader = CacheLoader.bulk(keys -> {
      return keys.stream().collect(toMap(identity(), identity()));
    });
    assertThat(loader.loadAll(Int.setOf(1, 2))).containsExactlyEntriesIn(Int.mapOf(1, 1, 2, 2));
    assertThat(loader.load(Int.valueOf(1))).isEqualTo(1);
  }

  /* --------------- Policy: refreshes --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_INCOMPLETE, implementation = Implementation.Caffeine)
  public void refreshes(LoadingCache<Int, Int> cache, CacheContext context) {
    var key1 = Iterables.get(context.absentKeys(), 0);
    var key2 = context.original().isEmpty()
        ? Iterables.get(context.absentKeys(), 1)
        : context.firstKey();
    var future1 = cache.refresh(key1);
    var future2 = cache.refresh(key2);
    assertThat(cache.policy().refreshes()).containsExactly(key1, future1, key2, future2);

    future1.complete(Int.valueOf(1));
    future2.cancel(true);
    assertThat(cache.policy().refreshes()).isExhaustivelyEmpty();
  }
}
